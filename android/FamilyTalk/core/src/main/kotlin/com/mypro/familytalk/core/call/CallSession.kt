package com.mypro.familytalk.core.call

/**
 * Pure call lifecycle. The app feeds it events (local actions, signalling, WebRTC
 * connection changes, timers) and acts on the returned [Effect]s. No Android or WebRTC
 * types here, so every transition is unit-tested.
 */
enum class CallPhase { OUTGOING, RINGING_OUT, INCOMING, CONNECTING, ACTIVE, ENDED }

sealed interface CallEvent {
    data object PeerRinging : CallEvent
    data object PeerAccepted : CallEvent
    data class PeerDeclined(val reason: String) : CallEvent
    data class PeerHungUp(val reason: String) : CallEvent
    data object LocalAccept : CallEvent
    data object LocalDecline : CallEvent
    data object LocalHangup : CallEvent
    data object MediaConnected : CallEvent
    data object MediaFailed : CallEvent
    data object MediaRecovered : CallEvent
    /** Timer tick; the session decides whether a timeout has been reached. */
    data class Tick(val now: Long) : CallEvent
}

sealed interface Effect {
    data object SendAccept : Effect
    data class SendDecline(val reason: String) : Effect
    data class SendHangup(val reason: String) : Effect
    /** Create the peer connection(s) and start offering per [Mesh] rules. */
    data object StartMedia : Effect
    data object StopMedia : Effect
    data object StartRingtone : Effect
    data object StopRingtone : Effect
    data object StartRingback : Effect
    data object StopRingback : Effect
    data object RestartIce : Effect
    data class Ended(val reason: String) : Effect
}

class CallSession(
    val callId: String,
    val outgoing: Boolean,
    startedAt: Long,
    private val ringTimeoutMs: Long = 45_000,
    private val connectTimeoutMs: Long = 25_000,
    private val reconnectGraceMs: Long = 15_000,
) {
    var phase: CallPhase = if (outgoing) CallPhase.OUTGOING else CallPhase.INCOMING
        private set
    var endReason: String? = null
        private set
    var connectedAt: Long? = null
        private set
    private var phaseSince = startedAt
    private var mediaLostAt: Long? = null

    fun start(): List<Effect> = if (outgoing) listOf(Effect.StartRingback) else listOf(Effect.StartRingtone)

    fun on(e: CallEvent, now: Long): List<Effect> {
        if (phase == CallPhase.ENDED) return emptyList()
        return when (e) {
            CallEvent.PeerRinging -> if (phase == CallPhase.OUTGOING) move(CallPhase.RINGING_OUT, now) else emptyList()
            CallEvent.PeerAccepted -> if (outgoing && phase in setOf(CallPhase.OUTGOING, CallPhase.RINGING_OUT)) {
                move(CallPhase.CONNECTING, now) + listOf(Effect.StopRingback, Effect.StartMedia)
            } else emptyList()
            is CallEvent.PeerDeclined -> end(e.reason, now, sendHangup = false)
            is CallEvent.PeerHungUp -> end(e.reason, now, sendHangup = false)
            CallEvent.LocalAccept -> if (phase == CallPhase.INCOMING) {
                move(CallPhase.CONNECTING, now) + listOf(Effect.StopRingtone, Effect.SendAccept, Effect.StartMedia)
            } else emptyList()
            CallEvent.LocalDecline -> if (phase == CallPhase.INCOMING) {
                listOf(Effect.SendDecline("declined")) + end("declined", now, sendHangup = false)
            } else emptyList()
            CallEvent.LocalHangup -> end("hangup", now, sendHangup = true)
            CallEvent.MediaConnected -> {
                mediaLostAt = null
                if (phase == CallPhase.CONNECTING) { connectedAt = now; move(CallPhase.ACTIVE, now) } else emptyList()
            }
            CallEvent.MediaRecovered -> { mediaLostAt = null; emptyList() }
            CallEvent.MediaFailed -> if (phase == CallPhase.ACTIVE && mediaLostAt == null) {
                mediaLostAt = now; listOf(Effect.RestartIce)
            } else emptyList()
            is CallEvent.Tick -> tick(e.now)
        }
    }

    private fun tick(now: Long): List<Effect> = when {
        phase in setOf(CallPhase.OUTGOING, CallPhase.RINGING_OUT) && now - phaseSince > ringTimeoutMs -> end("no answer", now, true)
        phase == CallPhase.INCOMING && now - phaseSince > ringTimeoutMs -> end("missed", now, false)
        phase == CallPhase.CONNECTING && now - phaseSince > connectTimeoutMs -> end("could not connect", now, true)
        phase == CallPhase.ACTIVE && mediaLostAt != null && now - mediaLostAt!! > reconnectGraceMs -> end("connection lost", now, true)
        else -> emptyList()
    }

    private fun move(p: CallPhase, now: Long): List<Effect> { phase = p; phaseSince = now; return emptyList() }

    private fun end(reason: String, now: Long, sendHangup: Boolean): List<Effect> {
        val wasRinging = phase
        phase = CallPhase.ENDED; phaseSince = now; endReason = reason
        return buildList {
            if (wasRinging == CallPhase.INCOMING) add(Effect.StopRingtone)
            if (wasRinging == CallPhase.OUTGOING || wasRinging == CallPhase.RINGING_OUT) add(Effect.StopRingback)
            if (sendHangup) add(Effect.SendHangup(reason))
            add(Effect.StopMedia)
            add(Effect.Ended(reason))
        }
    }
}

/**
 * Full-mesh conference rules. Every pair of participants has one peer connection; the
 * member with the lexicographically smaller id makes the offer, so two phones never
 * offer to each other at the same time (no "glare").
 */
object Mesh {
    /** Mesh upload grows with every participant; beyond this, phones overheat and audio breaks. */
    const val MAX_PARTICIPANTS = 6
    const val MAX_VIDEO_PARTICIPANTS = 4

    fun shouldOffer(me: String, peer: String): Boolean = me < peer

    fun peersFor(me: String, participants: List<String>): List<String> = participants.distinct().filter { it != me }

    fun canAdd(current: Int, video: Boolean): Boolean = current < if (video) MAX_VIDEO_PARTICIPANTS else MAX_PARTICIPANTS
}
