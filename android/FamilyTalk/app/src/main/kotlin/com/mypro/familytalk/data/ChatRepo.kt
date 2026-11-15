package com.mypro.familytalk.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.mypro.familytalk.core.crypto.Bytes
import com.mypro.familytalk.core.files.FileTransfer
import com.mypro.familytalk.core.protocol.ChatDelete
import com.mypro.familytalk.core.protocol.ChatReceipt
import com.mypro.familytalk.core.protocol.ChatText
import com.mypro.familytalk.core.protocol.FileChunk
import com.mypro.familytalk.core.protocol.FileOffer
import com.mypro.familytalk.core.protocol.FileResend
import com.mypro.familytalk.core.protocol.ViewedOnce
import com.mypro.familytalk.core.relay.Incoming
import kotlinx.coroutines.delay
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
    var onNewMessage: (ChatMsg, String) -> Unit = { _, _ -> }
    /** Conversation currently on screen; messages there are marked read immediately. */
    @Volatile var openConv: String? = null

    /** A file being received. Missing chunks are asked for again until it completes. */
    private class Download(val from: String, val asm: FileTransfer.Assembly) {
        var lastProgress = System.currentTimeMillis()
        var lastAsk = 0L
        var asks = 0
    }
    private val lock = Any()
    private val downloads = mutableMapOf<String, Download>()
    /** Chunks that arrived before their file offer (the relays do not keep order between them). */
    private val early = mutableMapOf<String, MutableList<Pair<Long, FileChunk>>>()
    private var earlyBytes = 0L
    /** Last time we re-sent a file to someone, so a looping requester cannot flood the relay. */
    private val served = mutableMapOf<String, Long>()

    init {
        family.handlers += ::handle
        family.onRosterChanged = ::recordRosterChange
        // Decrypted copies made for "open file" never outlive the app process.
        File(context.cacheDir, "open").deleteRecursively()
        purgeOldViewOnceCopies()
        family.scope.launch {
            delay(5_000)
            resumeInterruptedDownloads()
            while (true) { runCatching { tick() }; delay(10_000) }
        }
    }

    /** Visible, permanent record in the family chat of every change to who is in the family. */
    private fun recordRosterChange(old: com.mypro.familytalk.core.family.Roster?, new: com.mypro.familytalk.core.family.Roster) {
        if (old == null) return
        val lines = buildList {
            new.members.filter { old.byId(it.id) == null }.forEach { add("${it.name} joined the family (ext ${it.ext}). Their safety number is not verified yet.") }
            old.members.filter { new.byId(it.id) == null }.forEach { add("${it.name} (ext ${it.ext}) was removed from the family.") }
            new.members.forEach { m ->
                val o = old.byId(m.id) ?: return@forEach
                if (o.role != m.role) add("${m.name} is now ${if (m.role == com.mypro.familytalk.core.family.Role.ADMIN) "an admin" else "a member"}.")
                if (o.name != m.name) add("${o.name} was renamed to ${m.name}.")
            }
            if (old.relay != new.relay) add("The family's relay/TURN servers were changed by an admin.")
        }
        if (lines.isEmpty()) return
        val now = System.currentTimeMillis()
        mutate { h -> h.copy(chats = h.chats + lines.mapIndexed { i, t -> ChatMsg("sys-$now-$i", FAMILY_CONV, "system", now + i, text = t, status = MsgStatus.READ, system = true) }) }
    }

    private fun load(): History = runCatching {
        vault.read(FILE)?.let { json.decodeFromString(History.serializer(), String(it)) }
    }.getOrNull() ?: History()

    @Synchronized
    private fun mutate(f: (History) -> History) {
        val weekAgo = System.currentTimeMillis() - 7 * DAY
        _history.update { h ->
            f(h).let { it.copy(chats = it.chats.takeLast(MAX_MESSAGES), calls = it.calls.takeLast(500), tombstones = it.tombstones.filterValues { t -> t > weekAgo }) }
        }
        vault.write(FILE, json.encodeToString(History.serializer(), _history.value).toByteArray())
    }

    private fun updateMsg(id: String, from: String, f: (ChatMsg) -> ChatMsg) =
        mutate { h -> h.copy(chats = h.chats.map { if (it.id == id && it.from == from) f(it) else it }) }

    private fun find(id: String, from: String) = _history.value.chats.firstOrNull { it.id == id && it.from == from }

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

    fun sendFile(conv: String, name: String, mime: String, bytes: ByteArray, viewOnce: Boolean = false) {
        family.scope.launch {
            val (offer, chunks) = FileTransfer.prepare(if (viewOnce) "Photo.jpg" else name, mime, bytes, group = conv == FAMILY_CONV, viewOnce = viewOnce)
            // The sender keeps a copy so missed chunks can be re-sent; a view-once copy is hidden from the UI.
            val path = store(offer.fileId, bytes)
            val info = FileInfo(offer.name, offer.mime, offer.size, if (viewOnce) null else path, 1f, offer.sha256, offer.chunks, viewOnce)
            mutate { it.copy(chats = it.chats + ChatMsg(offer.fileId, conv, family.myId, offer.sentAt, file = info, status = MsgStatus.SENT)) }
            val to = recipients(conv)
            to.forEach { family.sendToMember(it, offer) }
            chunks.forEach { c ->
                to.forEach { family.sendToMember(it, c) }
                delay(PACE_MS) // gentle on the free public relays
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

    fun deleteConversation(conv: String) = mutate { h ->
        h.chats.filter { it.conv == conv }.forEach(::dropContent)
        h.copy(chats = h.chats.filter { it.conv != conv }, unread = h.unread - conv)
    }

    // ---- delete -------------------------------------------------------------------------

    /** Removes messages from this phone only. */
    fun deleteForMe(msgs: List<ChatMsg>) {
        val keys = msgs.map { it.from + "|" + it.id }.toSet()
        msgs.forEach(::dropContent)
        mutate { h -> h.copy(chats = h.chats.filterNot { (it.from + "|" + it.id) in keys }) }
    }

    /** True if "delete for everyone" is offered for this message. */
    fun canDeleteForEveryone(m: ChatMsg) =
        m.from == family.myId && !m.system && !m.deleted && System.currentTimeMillis() - m.at < DELETE_FOR_EVERYONE_MS

    /** Deletes our own messages on every family phone that received them, and here. */
    fun deleteForEveryone(msgs: List<ChatMsg>) {
        val mine = msgs.filter(::canDeleteForEveryone)
        if (mine.isEmpty()) return
        mine.groupBy { it.conv }.forEach { (conv, list) ->
            val payload = ChatDelete(list.map { it.id })
            recipients(conv).forEach { family.sendToMember(it, payload) }
        }
        mine.forEach { m -> dropContent(m); updateMsg(m.id, m.from) { it.copy(text = null, file = null, deleted = true) } }
    }

    private fun dropContent(m: ChatMsg) {
        m.file?.localPath?.let(::deleteFile)
        if (m.file != null) { deleteFile("vault:" + entry(m.id)); synchronized(lock) { downloads.remove(m.id) } }
    }

    // ---- view once ----------------------------------------------------------------------

    /** Decodes a received view-once photo for the in-app viewer (never written outside the vault). */
    fun openViewOnce(m: ChatMsg): Bitmap? {
        val f = m.file ?: return null
        if (!f.viewOnce || f.opened || m.from == family.myId) return null
        return f.localPath?.let { loadBitmap(it, 2048) }
    }

    /** Called when the view-once viewer closes: the photo is deleted for good and the sender told. */
    fun finishViewOnce(m: ChatMsg) {
        m.file?.localPath?.let(::deleteFile)
        updateMsg(m.id, m.from) { it.copy(file = it.file?.copy(localPath = null, opened = true)) }
        family.sendToMember(m.from, ViewedOnce(m.id))
    }

    private fun purgeOldViewOnceCopies() {
        val cutoff = System.currentTimeMillis() - 14 * DAY
        _history.value.chats.filter { it.from == family.myId && it.file?.viewOnce == true && it.at < cutoff }.forEach { deleteFile("vault:" + entry(it.id)) }
    }

    // ---- receiving ----------------------------------------------------------------------

    private fun handle(m: Incoming): Boolean {
        if (!m.fromKnownMember) return false
        val from = m.from.memberId
        when (val p = m.payload) {
            is ChatText -> {
                val conv = if (p.group) FAMILY_CONV else from
                if (find(p.msgId, from) != null) return true
                if (_history.value.tombstones.containsKey("$from|${p.msgId}")) return true
                val msg = ChatMsg(p.msgId, conv, from, p.sentAt, text = p.text.take(4000), status = MsgStatus.RECEIVED)
                receive(msg)
                if (!p.group) family.sendToMember(from, ChatReceipt(listOf(p.msgId), read = false))
            }
            is ChatReceipt -> mutate { h ->
                h.copy(chats = h.chats.map {
                    if (it.id in p.msgIds && it.from == family.myId && it.conv == from) it.copy(status = if (p.read) MsgStatus.READ else maxOf(it.status, MsgStatus.DELIVERED)) else it
                })
            }
            is FileOffer -> onOffer(from, p)
            is FileChunk -> onChunk(from, p)
            is FileResend -> onResend(from, p)
            is ChatDelete -> onDelete(from, p)
            is ViewedOnce -> {
                val msg = find(p.fileId, family.myId) ?: return true
                if (msg.file?.viewOnce != true || !canSee(from, msg)) return true
                updateMsg(msg.id, msg.from) { it.copy(file = it.file?.copy(opened = true)) }
                // In a 1-to-1 chat nobody else needs it any more.
                if (msg.conv != FAMILY_CONV) deleteFile("vault:" + entry(msg.id))
            }
            else -> return false
        }
        return true
    }

    private fun onOffer(from: String, p: FileOffer) {
        if (find(p.fileId, from) != null) return
        if (_history.value.tombstones.containsKey("$from|${p.fileId}")) return
        val asm = runCatching { FileTransfer.Assembly(p) }.getOrNull() ?: return
        val conv = if (p.group) FAMILY_CONV else from
        val name = if (p.viewOnce) "Photo" else FileTransfer.sanitizeName(p.name)
        receive(ChatMsg(p.fileId, conv, from, p.sentAt, file = FileInfo(name, p.mime, p.size, sha256 = p.sha256, chunks = p.chunks, viewOnce = p.viewOnce), status = MsgStatus.RECEIVED))
        startDownload(from, asm)
    }

    /** Starts (or resumes) receiving a file; returns false if too many are already running. */
    private fun startDownload(from: String, asm: FileTransfer.Assembly): Boolean {
        val id = asm.offer.fileId
        val buffered = synchronized(lock) {
            if (downloads.containsKey(id)) return true
            if (downloads.values.count { it.from == from } >= 3 || downloads.size >= 6) return false
            downloads[id] = Download(from, asm)
            early.remove(id).orEmpty().also { l -> earlyBytes -= l.sumOf { it.second.data.length.toLong() } }
        }
        buffered.forEach { onChunk(from, it.second) }
        return true
    }

    private fun onChunk(from: String, p: FileChunk) {
        val d = synchronized(lock) {
            downloads[p.fileId] ?: run {
                // Offer not here yet: keep the chunk for a few minutes (bounded memory).
                if (find(p.fileId, from)?.file?.localPath == null && earlyBytes + p.data.length < MAX_EARLY_BYTES) {
                    early.getOrPut(p.fileId) { mutableListOf() }.add(System.currentTimeMillis() to p)
                    earlyBytes += p.data.length
                }
                return
            }
        }
        if (d.from != from) return
        val before = d.asm.received
        runCatching { d.asm.add(p) }
        if (d.asm.received > before) d.lastProgress = System.currentTimeMillis()
        if (d.asm.complete) {
            synchronized(lock) { if (downloads.remove(p.fileId) == null) return }
            val result = runCatching { d.asm.finish() }
            result.onSuccess { bytes ->
                val path = store(p.fileId, bytes)
                updateMsg(p.fileId, from) { it.copy(status = if (it.status == MsgStatus.FAILED) MsgStatus.READ else it.status, file = it.file?.copy(localPath = path, progress = 1f)) }
            }.onFailure {
                updateMsg(p.fileId, from) { it.copy(status = MsgStatus.FAILED, file = it.file?.copy(progress = 0f)) }
            }
        } else if (d.asm.received % 6 == 0) {
            val progress = d.asm.received.toFloat() / d.asm.offer.chunks
            updateMsg(p.fileId, from) { it.copy(file = it.file?.copy(progress = progress)) }
        }
    }

    /** A family member asks us to send (part of) a file we sent them again. */
    private fun onResend(from: String, p: FileResend) {
        val msg = find(p.fileId, family.myId) ?: return
        if (msg.deleted || !canSee(from, msg)) return
        if (msg.file?.let { it.viewOnce && it.opened } == true && msg.conv != FAMILY_CONV) return
        val key = "$from|${p.fileId}"
        val now = System.currentTimeMillis()
        synchronized(lock) {
            if (now - (served[key] ?: 0) < 15_000) return
            served[key] = now
        }
        family.scope.launch {
            val bytes = runCatching { vault.read(entry(p.fileId)) }.getOrNull() ?: return@launch
            val only = p.missing.takeIf { it.isNotEmpty() }?.toSet()
            // Re-sent pieces go out on every relay: the one used first may be the one this phone can't reach.
            FileTransfer.chunks(p.fileId, bytes, only).forEach { family.sendToMember(from, it, spread = false); delay(PACE_MS) }
        }
    }

    private fun onDelete(from: String, p: ChatDelete) {
        val now = System.currentTimeMillis()
        p.msgIds.take(200).forEach { id ->
            val msg = find(id, from)
            if (msg == null) { mutate { h -> h.copy(tombstones = h.tombstones + ("$from|$id" to now)) }; return@forEach }
            dropContent(msg)
            updateMsg(id, from) { it.copy(text = null, file = null, deleted = true) }
        }
    }

    /** May [member] receive this message of ours? */
    private fun canSee(member: String, m: ChatMsg) =
        if (m.conv == FAMILY_CONV) family.roster.value?.byId(member) != null else m.conv == member

    /** Every 10 s: ask again for files that stopped arriving, start queued ones, drop stale early chunks. */
    private fun tick() {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            early.values.forEach { l -> l.removeAll { (at, c) -> (now - at > 5 * 60_000).also { if (it) earlyBytes -= c.data.length } } }
            early.entries.removeAll { it.value.isEmpty() }
            served.entries.removeAll { now - it.value > 60_000 }
        }
        val asks = synchronized(lock) {
            downloads.values.filter { d ->
                now - d.lastProgress > STALL_MS && now - d.lastAsk > askInterval(d.asks)
            }.onEach { it.lastAsk = now; it.asks++ }.map { it.from to FileResend(it.asm.offer.fileId, it.asm.missing().take(2000)) }
        }
        asks.forEach { (to, req) -> family.sendToMember(to, req) }
        // Files waiting for a free slot (more than 3 at once from one person).
        _history.value.chats.filter(::waitingDownload).take(6).forEach { requestDownload(it) }
    }

    private fun askInterval(asks: Int) = minOf(10 * 60_000L, 20_000L shl minOf(asks, 5))

    private fun waitingDownload(m: ChatMsg): Boolean {
        val f = m.file ?: return false
        return m.from != family.myId && !m.deleted && !f.opened && f.localPath == null && f.sha256 != null && m.status != MsgStatus.FAILED &&
            System.currentTimeMillis() - m.at < 30 * DAY && synchronized(lock) { !downloads.containsKey(m.id) }
    }

    private fun resumeInterruptedDownloads() {
        _history.value.chats.filter(::waitingDownload).forEach { requestDownload(it) }
    }

    /** "Tap to download": (re)starts receiving a file, asking the sender for every chunk we lack. */
    fun requestDownload(m: ChatMsg) {
        val f = m.file ?: return
        if (m.from == family.myId || f.localPath != null || f.opened || m.deleted) return
        val sha = f.sha256
        if (sha == null) {
            // Received by an older version that did not keep the details: we can't verify it, so ask for nothing.
            return
        }
        val offer = FileOffer(m.id, f.name, f.mime, f.size, sha, f.chunks, m.at, m.conv == FAMILY_CONV, f.viewOnce)
        val asm = runCatching { FileTransfer.Assembly(offer) }.getOrNull() ?: return
        synchronized(lock) { downloads[m.id]?.let { it.lastProgress = 0; it.lastAsk = 0; it.asks = 0 } }
        if (m.status == MsgStatus.FAILED) updateMsg(m.id, m.from) { it.copy(status = MsgStatus.READ, file = it.file?.copy(progress = 0f)) }
        if (!startDownload(m.from, asm)) return
        val d = synchronized(lock) { downloads[m.id]?.also { it.lastAsk = System.currentTimeMillis(); it.asks = 1 } } ?: return
        family.sendToMember(m.from, FileResend(m.id, d.asm.missing().take(2000)))
    }

    /** True while a file is actively being received (not just waiting). */
    fun isDownloading(id: String) = synchronized(lock) { downloads[id]?.let { System.currentTimeMillis() - it.lastProgress < 60_000 } ?: false }

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

    // ---- storage ------------------------------------------------------------------------

    private fun entry(fileId: String) = "file-" + fileId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }

    /** Files are kept inside the encrypted [Vault]; the returned handle is "vault:<entry>". */
    private fun store(fileId: String, bytes: ByteArray): String {
        vault.write(entry(fileId), bytes)
        return "vault:" + entry(fileId)
    }

    fun readFile(handle: String): ByteArray? =
        if (handle.startsWith("vault:")) runCatching { vault.read(handle.removePrefix("vault:")) }.getOrNull()
        else File(handle).takeIf { it.exists() }?.readBytes()

    /** Small preview of a stored picture (decoded in memory, never written out). */
    fun loadBitmap(handle: String, maxPx: Int): Bitmap? {
        val bytes = readFile(handle) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** Decrypts a stored file into a private cache folder so another app can open it. */
    fun exportForViewing(handle: String, name: String): File? {
        val bytes = readFile(handle) ?: return null
        val dir = File(context.cacheDir, "open").apply { mkdirs() }
        return File(dir, FileTransfer.sanitizeName(name)).also { it.writeBytes(bytes) }
    }

    /**
     * Saves a copy to the phone's shared storage: pictures/videos to the gallery (Pictures/FamilyTalk),
     * everything else to Downloads/FamilyTalk. Android 10+; older phones use [saveTo] with a picker.
     */
    fun saveToPhone(m: ChatMsg): Uri? {
        val f = m.file ?: return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        if (f.viewOnce) return null
        val bytes = f.localPath?.let(::readFile) ?: return null
        val media = f.mime.startsWith("image/") || f.mime.startsWith("video/")
        val collection = when {
            f.mime.startsWith("image/") -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            f.mime.startsWith("video/") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, FileTransfer.sanitizeName(f.name))
            put(MediaStore.MediaColumns.MIME_TYPE, f.mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, (if (media) Environment.DIRECTORY_PICTURES else Environment.DIRECTORY_DOWNLOADS) + "/FamilyTalk")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val cr = context.contentResolver
        val uri = cr.insert(collection, values) ?: return null
        return runCatching {
            cr.openOutputStream(uri)!!.use { it.write(bytes) }
            cr.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            uri
        }.getOrElse { cr.delete(uri, null, null); null }
    }

    /** Writes a stored file to a location the user picked (Android 9). */
    fun saveTo(m: ChatMsg, target: Uri): Boolean {
        val f = m.file ?: return false
        if (f.viewOnce) return false
        val bytes = f.localPath?.let(::readFile) ?: return false
        return runCatching { context.contentResolver.openOutputStream(target)!!.use { it.write(bytes) }; true }.getOrDefault(false)
    }

    fun deleteFile(handle: String) { if (handle.startsWith("vault:")) vault.delete(handle.removePrefix("vault:")) }

    private companion object {
        const val FILE = "history.v1"
        const val MAX_MESSAGES = 5000
        const val DAY = 24 * 60 * 60 * 1000L
        const val PACE_MS = 15L
        const val STALL_MS = 20_000L
        const val MAX_EARLY_BYTES = 24L * 1024 * 1024
        const val DELETE_FOR_EVERYONE_MS = 7 * DAY
    }
}
