package com.mypro.familytalk.data

import android.content.Context
import com.mypro.familytalk.core.crypto.Bytes
import com.mypro.familytalk.core.files.FileTransfer
import com.mypro.familytalk.core.protocol.ChatReceipt
import com.mypro.familytalk.core.protocol.ChatText
import com.mypro.familytalk.core.protocol.FileChunk
import com.mypro.familytalk.core.protocol.FileOffer
import com.mypro.familytalk.core.relay.Incoming
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.File

/** Chats, files and call history. Stored encrypted in the [Vault]. */
class ChatRepo(private val context: Context, private val family: FamilyRepo) {
    private val vault = Vault(context)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _history = MutableStateFlow(load())
    val history: StateFlow<History> = _history.asStateFlow()
    private val assemblies = mutableMapOf<String, Pair<String, FileTransfer.Assembly>>()
    var onNewMessage: (ChatMsg, String) -> Unit = { _, _ -> }
    /** Conversation currently on screen; messages there are marked read immediately. */
    @Volatile var openConv: String? = null

    init {
        family.handlers += ::handle
    }

    private fun load(): History = runCatching {
        vault.read(FILE)?.let { json.decodeFromString(History.serializer(), String(it)) }
    }.getOrNull() ?: History()

    @Synchronized
    private fun mutate(f: (History) -> History) {
        _history.update { h -> f(h).let { it.copy(chats = it.chats.takeLast(MAX_MESSAGES), calls = it.calls.takeLast(500)) } }
        vault.write(FILE, json.encodeToString(History.serializer(), _history.value).toByteArray())
    }

    fun messages(conv: String) = _history.value.chats.filter { it.conv == conv }

    private fun recipients(conv: String): List<String> =
        if (conv == FAMILY_CONV) family.roster.value?.members?.map { it.id }?.filter { it != family.myId } ?: emptyList()
        else listOf(conv)

    fun sendText(conv: String, text: String) {
        val t = text.trim().take(4000)
        if (t.isEmpty()) return
        val msg = ChatMsg(Bytes.b64(Bytes.random(12)), conv, family.myId, System.currentTimeMillis(), text = t, status = MsgStatus.SENT)
        mutate { it.copy(chats = it.chats + msg) }
        val payload = ChatText(msg.id, t, msg.at, group = conv == FAMILY_CONV)
        recipients(conv).forEach { family.sendToMember(it, payload) }
    }

    fun sendFile(conv: String, name: String, mime: String, bytes: ByteArray) {
        family.scope.launch {
            val (offer, chunks) = FileTransfer.prepare(name, mime, bytes, group = conv == FAMILY_CONV)
            val path = store(offer.fileId, offer.name, bytes)
            val msg = ChatMsg(offer.fileId, conv, family.myId, offer.sentAt, file = FileInfo(offer.name, offer.mime, offer.size, path, 1f), status = MsgStatus.SENT)
            mutate { it.copy(chats = it.chats + msg) }
            recipients(conv).forEach { to ->
                family.sendToMember(to, offer)
                chunks.forEach { family.sendToMember(to, it) }
            }
        }
    }

    fun markRead(conv: String) {
        val unreadIds = _history.value.chats.filter { it.conv == conv && it.from != family.myId && it.status == MsgStatus.RECEIVED }
        if (unreadIds.isEmpty() && (_history.value.unread[conv] ?: 0) == 0) return
        mutate { h -> h.copy(unread = h.unread - conv, chats = h.chats.map { if (it in unreadIds) it.copy(status = MsgStatus.READ) else it }) }
        if (conv != FAMILY_CONV && unreadIds.isNotEmpty()) family.sendToMember(conv, ChatReceipt(unreadIds.map { it.id }, read = true))
    }

    fun addCall(entry: CallLogEntry) = mutate { it.copy(calls = it.calls + entry) }

    fun deleteConversation(conv: String) = mutate { h -> h.copy(chats = h.chats.filter { it.conv != conv }, unread = h.unread - conv) }

    private fun handle(m: Incoming): Boolean {
        if (!m.fromKnownMember) return false
        val from = m.from.memberId
        when (val p = m.payload) {
            is ChatText -> {
                val conv = if (p.group) FAMILY_CONV else from
                if (_history.value.chats.any { it.id == p.msgId && it.from == from }) return true
                val msg = ChatMsg(p.msgId, conv, from, p.sentAt, text = p.text.take(4000), status = MsgStatus.RECEIVED)
                receive(msg)
                if (!p.group) family.sendToMember(from, ChatReceipt(listOf(p.msgId), read = false))
            }
            is ChatReceipt -> mutate { h ->
                h.copy(chats = h.chats.map {
                    if (it.id in p.msgIds && it.from == family.myId && it.conv == from) it.copy(status = if (p.read) MsgStatus.READ else maxOf(it.status, MsgStatus.DELIVERED)) else it
                })
            }
            is FileOffer -> {
                val asm = runCatching { FileTransfer.Assembly(p) }.getOrNull() ?: return true
                synchronized(assemblies) { assemblies[p.fileId] = from to asm }
                val conv = if (p.group) FAMILY_CONV else from
                receive(ChatMsg(p.fileId, conv, from, p.sentAt, file = FileInfo(FileTransfer.sanitizeName(p.name), p.mime, p.size), status = MsgStatus.RECEIVED))
            }
            is FileChunk -> {
                val (owner, asm) = synchronized(assemblies) { assemblies[p.fileId] } ?: return true
                if (owner != from) return true
                runCatching { asm.add(p) }
                val progress = asm.received.toFloat() / asm.offer.chunks
                if (asm.complete) {
                    synchronized(assemblies) { assemblies.remove(p.fileId) }
                    val result = runCatching { asm.finish() }
                    mutate { h ->
                        h.copy(chats = h.chats.map { c ->
                            if (c.id == p.fileId && c.from == from) {
                                result.fold(
                                    { bytes -> c.copy(file = c.file?.copy(localPath = store(p.fileId, asm.offer.name, bytes), progress = 1f)) },
                                    { c.copy(status = MsgStatus.FAILED, file = c.file?.copy(progress = 0f)) },
                                )
                            } else c
                        })
                    }
                } else if (asm.received % 8 == 0) {
                    mutate { h -> h.copy(chats = h.chats.map { c -> if (c.id == p.fileId && c.from == from) c.copy(file = c.file?.copy(progress = progress)) else c }) }
                }
            }
            else -> return false
        }
        return true
    }

    private fun receive(msg: ChatMsg) {
        val visible = openConv == msg.conv
        mutate { h ->
            h.copy(
                chats = h.chats + if (visible) msg.copy(status = MsgStatus.READ) else msg,
                unread = if (visible) h.unread else h.unread + (msg.conv to ((h.unread[msg.conv] ?: 0) + 1)),
            )
        }
        if (visible && msg.conv != FAMILY_CONV) family.sendToMember(msg.from, ChatReceipt(listOf(msg.id), read = true))
        val name = family.roster.value?.byId(msg.from)?.name ?: "Family"
        if (!visible) onNewMessage(msg, name)
    }

    private fun store(fileId: String, name: String, bytes: ByteArray): String {
        val dir = File(context.filesDir, "received/${fileId.filter { it.isLetterOrDigit() }}").apply { mkdirs() }
        val f = File(dir, FileTransfer.sanitizeName(name))
        f.writeBytes(bytes)
        return f.absolutePath
    }

    private companion object {
        const val FILE = "history.v1"
        const val MAX_MESSAGES = 5000
    }
}
