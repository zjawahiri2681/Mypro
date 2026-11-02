package com.mypro.familytalk.call

import android.content.Context
import android.content.Intent
import android.util.Log
import com.mypro.familytalk.core.call.CallEvent
import com.mypro.familytalk.core.call.CallPhase
import com.mypro.familytalk.core.call.CallSession
import com.mypro.familytalk.core.call.Effect
import com.mypro.familytalk.core.call.Mesh
import com.mypro.familytalk.core.crypto.Bytes
import com.mypro.familytalk.core.family.Member
import com.mypro.familytalk.core.protocol.CallAccept
import com.mypro.familytalk.core.protocol.CallDecline
import com.mypro.familytalk.core.protocol.CallHangup
import com.mypro.familytalk.core.protocol.CallInvite
import com.mypro.familytalk.core.protocol.CallMembers
import com.mypro.familytalk.core.protocol.CallRinging
import com.mypro.familytalk.core.protocol.Ice
import com.mypro.familytalk.core.protocol.MediaState
import com.mypro.familytalk.core.protocol.Payload
import com.mypro.familytalk.core.protocol.Sdp
import com.mypro.familytalk.core.relay.Incoming
import com.mypro.familytalk.data.CallLogEntry
import com.mypro.familytalk.data.ChatRepo
import com.mypro.familytalk.data.FamilyRepo
import com.mypro.familytalk.service.LinkService
import com.mypro.familytalk.ui.CallActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.VideoTrack
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

data class PeerUi(val memberId: String, val name: String, val ext: Int?, val state: String, val video: VideoTrack?, val camOn: Boolean = true, val micOn: Boolean = true)

data class CallUi(
    val callId: String,
    val phase: CallPhase,
    val outgoing: Boolean,
    val video: Boolean,
    val title: String,
    val peers: List<PeerUi>,
    val mic: Boolean = true,
    val camera: Boolean = true,
    val speaker: Boolean = false,
    val connectedAt: Long? = null,
    val endReason: String? = null,
    val conference: Boolean = false,
    val verified: Boolean = false,
    val echoTest: Boolean = false,
)

/**
 * Drives one family call (1:1 or mesh conference) on a single thread: signalling in,
 * [CallSession] decides, WebRTC acts.
 */
class CallManager(private val context: Context, private val family: FamilyRepo, private val chats: ChatRepo) {
    val rtc = WebRtc(context)
    private val audio = CallAudio(context)
    private val exec = Executors.newSingleThreadScheduledExecutor { Thread(it, "ft-call") }
    private fun post(block: () -> Unit) = exec.execute { runCatching(block).onFailure { Log.e(TAG, "call error", it) } }

    private val _ui = MutableStateFlow<CallUi?>(null)
    val ui: StateFlow<CallUi?> = _ui.asStateFlow()

    private var session: CallSession? = null
    private var participants: List<String> = emptyList()
    private val joined = mutableSetOf<String>()
    private val gone = mutableSetOf<String>()
    private val peers = mutableMapOf<String, Peer>()
    private val earlyIce = mutableMapOf<String, MutableList<IceCandidate>>()
    private val peerState = mutableMapOf<String, PeerConnection.PeerConnectionState>()
    private val remoteMedia = mutableMapOf<String, MediaState>()
    private var video = false
    private var ticker: ScheduledFuture<*>? = null
    private var echo: Pair<Peer, Peer>? = null

    init {
        family.handlers += { m -> if (isCallPayload(m.payload) && m.fromKnownMember) { post { onSignal(m) }; true } else false }
    }

    val inCall: Boolean get() = session != null || echo != null

    /** True while a SIP call holds the microphone; family calls are refused as busy meanwhile. */
    var externalBusy: () -> Boolean = { false }

    // ---- local actions -----------------------------------------------------------------
    fun call(members: List<Member>, withVideo: Boolean) = post {
        if (inCall || externalBusy()) return@post
        val me = family.myId
        val targets = members.filter { it.id != me }.take(if (withVideo) Mesh.MAX_VIDEO_PARTICIPANTS - 1 else Mesh.MAX_PARTICIPANTS - 1)
        if (targets.isEmpty()) return@post
        val id = Bytes.b64(Bytes.random(12))
        reset(id, outgoing = true, withVideo, listOf(me) + targets.map { it.id })
        joined += me
        val invite = CallInvite(id, withVideo, participants)
        targets.forEach { family.send(it.identity, invite) }
        apply(session!!.start())
        rtc.startLocal(withVideo)
        audio.enterCall(speaker = withVideo)
        publish()
        openCallScreen()
    }

    fun accept() = post { event(CallEvent.LocalAccept) }
    fun decline() = post { event(CallEvent.LocalDecline) }
    fun hangup() = post {
        if (echo != null) { stopEcho(); return@post }
        event(CallEvent.LocalHangup)
    }

    fun toggleMic() = post { val on = !(_ui.value?.mic ?: true); rtc.setMic(on); _ui.value = _ui.value?.copy(mic = on); broadcastMedia() }
    fun toggleCamera() = post {
        val cur = _ui.value ?: return@post
        if (rtc.videoTrack == null) return@post // audio-only call: the camera button is hidden
        val on = !cur.camera; rtc.setCamera(on); _ui.value = cur.copy(camera = on); broadcastMedia()
    }
    fun toggleSpeaker() = post { val on = !(_ui.value?.speaker ?: false); audio.setSpeaker(on); _ui.value = _ui.value?.copy(speaker = on) }
    fun switchCamera() = post { rtc.switchCamera() }

    /** Adds someone to the running call (turns it into a conference). */
    fun addPerson(m: Member) = post {
        val s = session ?: return@post
        if (m.id in participants || !Mesh.canAdd(participants.size, video)) return@post
        participants = participants + m.id
        gone -= m.id
        family.send(m.identity, CallInvite(s.callId, video, participants))
        participants.filter { it != family.myId && it != m.id }.forEach { family.sendToMember(it, CallMembers(s.callId, participants)) }
        publish()
    }

    /** Local loopback through the real WebRTC stack: you hear yourself after a round trip. */
    fun echoTest() = post {
        if (inCall) return@post
        rtc.startLocal(false)
        audio.enterCall(speaker = false)
        lateinit var a: Peer
        lateinit var b: Peer
        val wire = object : Peer.Listener {
            override fun onLocalSdp(peer: Peer, type: String, sdp: String) { (if (peer === a) b else a).onRemoteSdp(type, sdp) }
            override fun onLocalIce(peer: Peer, c: IceCandidate) { (if (peer === a) b else a).addIce(c) }
            override fun onState(peer: Peer, state: PeerConnection.PeerConnectionState) {
                if (peer === a) post { _ui.value = _ui.value?.copy(phase = if (state == PeerConnection.PeerConnectionState.CONNECTED) CallPhase.ACTIVE else CallPhase.CONNECTING, connectedAt = _ui.value?.connectedAt ?: System.currentTimeMillis()) }
            }
            override fun onRemoteVideo(peer: Peer, track: VideoTrack) {}
        }
        val ice = family.roster.value?.relay?.ice ?: emptyList()
        a = rtc.createPeer("echo-a", ice, false, wire) ?: return@post
        b = rtc.createPeer("echo-b", ice, false, wire) ?: return@post
        echo = a to b
        _ui.value = CallUi("echo", CallPhase.CONNECTING, true, false, "Echo test (*43)", listOf(PeerUi("echo", "You, after a round trip", null, "connecting", null)), echoTest = true)
        a.offer()
        openCallScreen()
    }

    private fun stopEcho() {
        echo?.let { it.first.close(); it.second.close() }; echo = null
        rtc.stopLocal(); audio.exitCall()
        _ui.value = _ui.value?.copy(phase = CallPhase.ENDED, endReason = "Echo test finished")
        exec.schedule({ if (session == null && echo == null) _ui.value = null }, 1500, TimeUnit.MILLISECONDS)
    }

    // ---- signalling in -----------------------------------------------------------------
    private fun onSignal(m: Incoming) {
        val from = m.from.memberId
        when (val p = m.payload) {
            is CallInvite -> onInvite(from, p)
            is CallRinging -> if (p.callId == session?.callId && participants.size == 2) event(CallEvent.PeerRinging)
            is CallAccept -> onAccept(from, p)
            is CallDecline -> onLeft(from, p.callId, p.reason, declined = true)
            is CallHangup -> onLeft(from, p.callId, p.reason, declined = false)
            is CallMembers -> if (p.callId == session?.callId && from in participants) {
                participants = p.participants.distinct(); publish()
            }
            is Sdp -> if (p.callId == session?.callId && from in participants) onSdp(from, p)
            is Ice -> if (p.callId == session?.callId && from in participants) {
                val c = IceCandidate(p.mid, p.index, p.candidate)
                peers[from]?.addIce(c) ?: earlyIce.getOrPut(from) { mutableListOf() }.add(c)
            }
            is MediaState -> if (p.callId == session?.callId) { remoteMedia[from] = p; publish() }
            else -> {}
        }
    }

    private fun onInvite(from: String, p: CallInvite) {
        val me = family.myId
        if (me !in p.participants || from !in p.participants) return
        if (inCall || externalBusy()) {
            if (p.callId != session?.callId) family.sendToMember(from, CallDecline(p.callId, "busy"))
            return
        }
        reset(p.callId, outgoing = false, p.video, p.participants.distinct())
        joined += from
        family.sendToMember(from, CallRinging(p.callId))
        apply(session!!.start())
        publish()
        LinkService.showIncoming(context, _ui.value!!)
    }

    private fun onAccept(from: String, p: CallAccept) {
        val s = session ?: return
        if (p.callId != s.callId || from !in participants) return
        val wasJoined = from in joined
        joined += from
        gone -= from
        if (s.outgoing && s.phase in setOf(CallPhase.OUTGOING, CallPhase.RINGING_OUT)) {
            event(CallEvent.PeerAccepted) // -> StartMedia connects to everyone joined
        } else if (s.phase == CallPhase.CONNECTING || s.phase == CallPhase.ACTIVE) {
            connect(from)
            // Someone new joined a running conference: tell them we are in, so whichever
            // of us has the smaller id makes the offer (see Mesh.shouldOffer).
            if (!wasJoined) family.sendToMember(from, CallAccept(s.callId))
        }
        publish()
    }

    private fun onLeft(from: String, callId: String, reason: String, declined: Boolean) {
        val s = session ?: return
        if (callId != s.callId || from !in participants) return
        gone += from
        joined -= from
        peers.remove(from)?.close()
        val others = participants.filter { it != family.myId }
        val incomingFromCaller = !s.outgoing && s.phase == CallPhase.INCOMING
        when {
            others.size == 1 -> event(if (declined) CallEvent.PeerDeclined(reasonText(reason)) else CallEvent.PeerHungUp(reasonText(reason)))
            incomingFromCaller && joined.isEmpty() -> event(CallEvent.PeerHungUp("cancelled"))
            others.all { it in gone } -> event(CallEvent.PeerHungUp(if (declined) reasonText(reason) else "everyone left"))
            else -> { updateMediaPhase(); publish() }
        }
    }

    private fun onSdp(from: String, p: Sdp) {
        val s = session ?: return
        if (s.phase !in setOf(CallPhase.CONNECTING, CallPhase.ACTIVE)) return
        val peer = peers[from] ?: if (p.type == "offer") createPeer(from) else null
        peer?.onRemoteSdp(p.type, p.sdp)
    }

    // ---- session plumbing --------------------------------------------------------------
    private fun event(e: CallEvent) {
        val s = session ?: return
        apply(s.on(e, System.currentTimeMillis()))
        publish()
    }

    private fun apply(effects: List<Effect>) {
        val s = session ?: return
        val others = participants.filter { it != family.myId }
        for (fx in effects) when (fx) {
            Effect.SendAccept -> { joined += family.myId; others.filter { it !in gone }.forEach { family.sendToMember(it, CallAccept(s.callId)) } }
            is Effect.SendDecline -> others.forEach { family.sendToMember(it, CallDecline(s.callId, fx.reason)) }
            is Effect.SendHangup -> others.filter { it !in gone }.forEach { family.sendToMember(it, CallHangup(s.callId, fx.reason)) }
            Effect.StartMedia -> {
                LinkService.enterCall(context, video)
                rtc.startLocal(video)
                audio.enterCall(speaker = video)
                joined.filter { it != family.myId }.forEach { connect(it) }
            }
            Effect.StopMedia -> { peers.values.forEach { it.close() }; peers.clear(); rtc.stopLocal() }
            Effect.StartRingtone -> audio.startRingtone()
            Effect.StopRingtone -> { audio.stopRingtone(); LinkService.cancelIncoming(context) }
            Effect.StartRingback -> audio.startRingback()
            Effect.StopRingback -> audio.stopRingback()
            Effect.RestartIce -> peers.values.filter { Mesh.shouldOffer(family.myId, it.peerId) }.forEach { it.offer(iceRestart = true) }
            is Effect.Ended -> finish(fx.reason)
        }
    }

    private fun connect(peerId: String) {
        if (peerId == family.myId || peers.containsKey(peerId)) return
        val peer = createPeer(peerId) ?: return
        if (Mesh.shouldOffer(family.myId, peerId)) peer.offer()
    }

    private fun createPeer(peerId: String): Peer? {
        val s = session ?: return null
        val cfg = family.roster.value?.relay ?: return null
        val peer = rtc.createPeer(peerId, cfg.ice, family.data.value.relayOnly, object : Peer.Listener {
            override fun onLocalSdp(peer: Peer, type: String, sdp: String) = family.sendToMember(peerId, Sdp(s.callId, type, sdp))
            override fun onLocalIce(peer: Peer, c: IceCandidate) = family.sendToMember(peerId, Ice(s.callId, c.sdpMid, c.sdpMLineIndex, c.sdp))
            override fun onState(peer: Peer, state: PeerConnection.PeerConnectionState) = post {
                if (peers[peerId] !== peer) return@post
                peerState[peerId] = state
                updateMediaPhase(); publish()
            }
            override fun onRemoteVideo(peer: Peer, track: VideoTrack) = post { publish() }
        }) ?: return null
        peers[peerId] = peer
        earlyIce.remove(peerId)?.forEach(peer::addIce)
        return peer
    }

    private fun updateMediaPhase() {
        val states = peers.keys.mapNotNull { peerState[it] }
        when {
            states.any { it == PeerConnection.PeerConnectionState.CONNECTED } -> { event(CallEvent.MediaConnected); event(CallEvent.MediaRecovered) }
            states.isNotEmpty() && states.all { it == PeerConnection.PeerConnectionState.FAILED || it == PeerConnection.PeerConnectionState.DISCONNECTED } -> event(CallEvent.MediaFailed)
        }
    }

    private fun reset(callId: String, outgoing: Boolean, withVideo: Boolean, people: List<String>) {
        session = CallSession(callId, outgoing, System.currentTimeMillis())
        participants = people; video = withVideo
        joined.clear(); gone.clear(); peers.clear(); earlyIce.clear(); peerState.clear(); remoteMedia.clear()
        _ui.value = null
        ticker?.cancel()
        ticker = exec.scheduleWithFixedDelay({ post { session?.let { event(CallEvent.Tick(System.currentTimeMillis())) } } }, 1, 1, TimeUnit.SECONDS)
    }

    private fun finish(reason: String) {
        val s = session ?: return
        ticker?.cancel()
        audio.exitCall()
        LinkService.cancelIncoming(context)
        LinkService.exitCall(context)
        val dur = s.connectedAt?.let { (System.currentTimeMillis() - it) / 1000 } ?: 0
        chats.addCall(CallLogEntry(s.callId, participants.filter { it != family.myId }, s.outgoing, video, System.currentTimeMillis() - dur * 1000, dur, reason))
        if (!s.outgoing && s.connectedAt == null && reason in setOf("missed", "cancelled")) {
            LinkService.notifyMissed(context, participants.firstOrNull { it != family.myId }?.let { family.roster.value?.byId(it)?.name } ?: "Family")
        }
        publish()
        session = null
        exec.schedule({ if (session == null && echo == null) _ui.value = null }, 1500, TimeUnit.MILLISECONDS)
    }

    private fun publish() {
        val s = session ?: return
        val roster = family.roster.value
        val others = participants.filter { it != family.myId }
        val peerUis = others.map { id ->
            val m = roster?.byId(id)
            val st = when {
                id in gone -> "left"
                peerState[id] == PeerConnection.PeerConnectionState.CONNECTED -> "connected"
                peers.containsKey(id) -> "connecting"
                id in joined -> "in call"
                else -> "ringing"
            }
            val rm = remoteMedia[id]
            PeerUi(id, m?.name ?: "Unknown", m?.ext, st, peers[id]?.remoteVideo, rm?.camera ?: video, rm?.mic ?: true)
        }
        val title = if (others.size == 1) peerUis.first().name else "Family call (${others.size + 1})"
        val prev = _ui.value
        _ui.value = CallUi(
            s.callId, s.phase, s.outgoing, video, title, peerUis,
            mic = prev?.mic ?: true, camera = prev?.camera ?: video, speaker = prev?.speaker ?: video,
            connectedAt = s.connectedAt, endReason = s.endReason, conference = others.size > 1,
            verified = others.all { it in family.data.value.verified },
        )
    }

    private fun broadcastMedia() {
        val s = session ?: return
        val ui = _ui.value ?: return
        joined.filter { it != family.myId }.forEach { family.sendToMember(it, MediaState(s.callId, ui.mic, ui.camera)) }
    }

    private fun openCallScreen() {
        context.startActivity(Intent(context, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun reasonText(r: String) = when (r) {
        "busy" -> "busy on another call"
        "declined" -> "declined"
        else -> r
    }

    companion object {
        private const val TAG = "CallManager"
        fun isCallPayload(p: Payload) = p is CallInvite || p is CallRinging || p is CallAccept || p is CallDecline ||
            p is CallHangup || p is CallMembers || p is Sdp || p is Ice || p is MediaState
    }
}
