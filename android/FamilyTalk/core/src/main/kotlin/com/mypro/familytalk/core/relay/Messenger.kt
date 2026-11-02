package com.mypro.familytalk.core.relay

import com.mypro.familytalk.core.crypto.Envelope
import com.mypro.familytalk.core.crypto.IdentityKeys
import com.mypro.familytalk.core.crypto.PrivatePrekey
import com.mypro.familytalk.core.crypto.PublicIdentity
import com.mypro.familytalk.core.crypto.SignedPrekey
import com.mypro.familytalk.core.crypto.SealException
import com.mypro.familytalk.core.crypto.Sealed
import com.mypro.familytalk.core.protocol.ChatReceipt
import com.mypro.familytalk.core.protocol.ChatText
import com.mypro.familytalk.core.protocol.FileChunk
import com.mypro.familytalk.core.protocol.FileOffer
import com.mypro.familytalk.core.protocol.JoinRequest
import com.mypro.familytalk.core.protocol.Payload
import com.mypro.familytalk.core.protocol.PayloadCodec
import com.mypro.familytalk.core.protocol.ReplayGuard
import com.mypro.familytalk.core.protocol.RosterUpdate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What actually goes on the wire: the envelope, plus the joiner's keys for a first-contact join request. */
@Serializable
data class Wire(val env: Envelope, val joiner: PublicIdentity? = null)

data class Incoming(val from: PublicIdentity, val payload: Payload, val sentAt: Long, val fromKnownMember: Boolean)

/**
 * Seals, routes and opens messages. Trust decisions are delegated to [trusted]: only
 * keys returned there (the signed roster + the admin pinned from the invite) can
 * deliver anything other than a join request.
 */
class Messenger(
    private val me: IdentityKeys,
    private val familyId: () -> String,
    private val trusted: () -> Map<String, PublicIdentity>,
    private val relay: Relay,
    private val guard: ReplayGuard = ReplayGuard(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
    /**
     * Call signalling older than this is dropped. Set to the process start time so a
     * recorded "invite" cannot be replayed to make a phone ring after an app restart
     * (the in-memory replay cache is empty then).
     */
    private val signalNotBefore: Long = 0,
    /** Our private prekeys by id (for decrypting). */
    private val myPrekeys: (String) -> PrivatePrekey? = { null },
    /** Newest verified prekey of a peer (for encrypting), or null to use their identity key. */
    private val peerPrekey: (String) -> SignedPrekey? = { null },
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val outbox = ArrayDeque<Pair<String, ByteArray>>()
    var onIncoming: (Incoming) -> Unit = {}

    fun start() {
        relay.start(Topics.inbox(familyId(), me.memberId)) { receive(it) }
    }

    fun stop() = relay.stop()

    /** Durable payloads (chat, files, roster) are queued by the broker for offline phones and locally while we are offline. */
    fun send(to: PublicIdentity, payload: Payload, joining: Boolean = false) {
        val durable = isDurable(payload)
        val env = Sealed.seal(PayloadCodec.encode(payload), familyId(), me, to, clock(), prekey = if (joining) null else peerPrekey(to.memberId))
        val inner = json.encodeToString(Wire.serializer(), Wire(env, if (joining) me.public else null)).toByteArray()
        val wire = Sealed.sealOuter(inner, to.dhKeyBytes)
        val topic = Topics.inbox(familyId(), to.memberId)
        val brokerQueued = durable || queueAtBroker(payload)
        if (!relay.connected.value && durable) { enqueue(topic, wire); return }
        if (!relay.publish(topic, wire, brokerQueued) && durable) enqueue(topic, wire)
    }

    private fun enqueue(topic: String, wire: ByteArray) {
        synchronized(outbox) { outbox.addLast(topic to wire); while (outbox.size > 2000) outbox.removeFirst() }
    }

    /** Call when the relay reconnects. */
    fun flushOutbox() {
        val items = synchronized(outbox) { outbox.toList().also { outbox.clear() } }
        items.forEach { (t, w) -> if (!relay.publish(t, w, true)) enqueue(t, w) }
    }

    internal fun receive(bytes: ByteArray) {
        if (bytes.size > Sealed.MAX_PAYLOAD * 4) return
        val inner = try { Sealed.openOuter(bytes, me) } catch (e: SealException) { return }
        val wire = try { json.decodeFromString(Wire.serializer(), String(inner, Charsets.UTF_8)) } catch (e: Exception) { return }
        val env = wire.env
        if (env.family != familyId() || env.to != me.memberId) return
        val known = trusted()[env.from]
        val sender = known ?: wire.joiner?.takeIf { it.memberId == env.from } ?: return
        val payload = try {
            PayloadCodec.decode(Sealed.open(env, me, sender, myPrekeys))
        } catch (e: SealException) {
            log("dropped envelope from ${env.from.take(8)}: ${e.message}"); return
        } catch (e: Exception) {
            log("undecodable payload from ${env.from.take(8)}"); return
        }
        // Strangers may only ask to join; everything else requires roster membership.
        if (known == null && payload !is JoinRequest) return
        val window = if (isDurable(payload)) DURABLE_WINDOW else SIGNAL_WINDOW
        if (!isDurable(payload) && signalNotBefore > 0 && env.ts < signalNotBefore) return
        // Phones with a wrong clock must still be able to chat and join; signalling stays strict.
        val skew = if (isDurable(payload)) DURABLE_FUTURE_SKEW else SIGNAL_FUTURE_SKEW
        if (!guard.accept(env.from, env.id, env.ts, clock(), window, skew)) { log("dropped (time/replay) from ${env.from.take(8)}"); return }
        onIncoming(Incoming(sender, payload, env.ts, known != null))
    }

    companion object {
        /**
         * Phones' clocks are often minutes apart (tablets especially), so the time check is
         * only a coarse filter; real replay protection is the persisted message-id cache.
         */
        const val SIGNAL_WINDOW = 30 * 60 * 1000L
        const val DURABLE_WINDOW = 7 * 24 * 60 * 60 * 1000L
        const val DURABLE_FUTURE_SKEW = 24 * 60 * 60 * 1000L
        const val SIGNAL_FUTURE_SKEW = 30 * 60 * 1000L
        /**
         * Ringing, cancelling and declining are queued by the broker (QoS 1) so a phone
         * whose connection blipped still rings; stale ones are dropped by the 2-minute window.
         */
        fun queueAtBroker(p: Payload) = p is com.mypro.familytalk.core.protocol.CallInvite ||
            p is com.mypro.familytalk.core.protocol.CallHangup || p is com.mypro.familytalk.core.protocol.CallDecline ||
            p is com.mypro.familytalk.core.protocol.CallAccept || p is com.mypro.familytalk.core.protocol.Sdp ||
            p is com.mypro.familytalk.core.protocol.Ice

        fun isDurable(p: Payload) = p is ChatText || p is ChatReceipt || p is FileOffer || p is FileChunk || p is RosterUpdate || p is JoinRequest ||
            p is com.mypro.familytalk.core.protocol.PrekeyAnnounce
    }
}
