package com.mypro.familytalk.core.relay

import com.mypro.familytalk.core.crypto.Bytes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Byte pipe between devices. Implementations never see plaintext. */
interface Relay {
    val connected: StateFlow<Boolean>
    /** Starts delivering messages published to [inbox] to [onMessage]. Reconnects by itself. */
    fun start(inbox: String, onMessage: (ByteArray) -> Unit)
    fun publish(topic: String, data: ByteArray, queueIfOffline: Boolean)
    fun stop()
}

object Topics {
    /**
     * Inbox topic for one member. Hashed with the family id so the broker (and anyone
     * listening on a public broker) learns neither who is talking nor which family.
     */
    fun inbox(familyId: String, memberId: String): String =
        "ftalk/v1/" + Bytes.hex(Bytes.sha256(Bytes.utf8("ft-topic"), Bytes.utf8(familyId), Bytes.utf8(memberId)).copyOf(20))
}

/** In-process relay for tests and the echo test. */
class LoopbackRelay(private val hub: Hub = Hub()) : Relay {
    class Hub { val inboxes = mutableMapOf<String, (ByteArray) -> Unit>(); val offline = mutableMapOf<String, MutableList<ByteArray>>() }
    override val connected = MutableStateFlow(false)
    private var mine: String? = null

    override fun start(inbox: String, onMessage: (ByteArray) -> Unit) {
        synchronized(hub) {
            mine = inbox
            hub.inboxes[inbox] = onMessage
            hub.offline.remove(inbox)?.forEach(onMessage)
        }
        connected.value = true
    }

    override fun publish(topic: String, data: ByteArray, queueIfOffline: Boolean) {
        val target = synchronized(hub) {
            hub.inboxes[topic] ?: run { if (queueIfOffline) hub.offline.getOrPut(topic) { mutableListOf() }.add(data); null }
        }
        target?.invoke(data)
    }

    override fun stop() { synchronized(hub) { mine?.let { hub.inboxes.remove(it) } }; connected.value = false }
}
