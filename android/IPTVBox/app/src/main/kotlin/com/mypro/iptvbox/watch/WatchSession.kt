package com.mypro.iptvbox.watch

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.Player
import com.mypro.iptvbox.core.sync.ClockOffsetEstimator
import com.mypro.iptvbox.core.sync.LanClientTransport
import com.mypro.iptvbox.core.sync.LanHostTransport
import com.mypro.iptvbox.core.sync.MediaRef
import com.mypro.iptvbox.core.sync.MqttTransport
import com.mypro.iptvbox.core.sync.SyncCrypto
import com.mypro.iptvbox.core.sync.SyncMath
import com.mypro.iptvbox.core.sync.SyncMessage
import com.mypro.iptvbox.core.sync.SyncTransport
import com.mypro.iptvbox.core.sync.WatchLink
import com.mypro.iptvbox.core.util.Ids
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App-wide watch-together session. Hosts publish their player state; viewers follow it.
 * Everybody can press play/pause/seek - viewers send a command, the host applies and re-broadcasts.
 */
object WatchSession {
    private const val TAG = "WatchSession"

    enum class Role { NONE, HOST, VIEWER }

    data class State(
        val role: Role = Role.NONE,
        val link: WatchLink? = null,
        val status: String = "",
        val connected: Boolean = false,
        val peers: Int = 0,
        val lastRemoteName: String = "",
        val rttMs: Long = 0L,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    val active: Boolean get() = _state.value.role != Role.NONE

    val selfId: String = Ids.random()
    private var displayName: String = "Me"
    private var transport: SyncTransport? = null
    private var player: Player? = null
    private val handler = Handler(Looper.getMainLooper())
    private val clock = ClockOffsetEstimator()
    private var lastHostState: SyncMessage? = null
    private var mediaId: String = ""
    private var applyingRemote = false
    private var lastRateSet = 1f
    private val knownPeers = LinkedHashMap<String, Long>()

    /** Hooked by the player screen so viewers get a toast-like status line. */
    var onEvent: (String) -> Unit = {}

    // ------------------------------------------------------------ lifecycle

    fun host(mode: WatchLink.Mode, media: MediaRef, broker: String, lanHost: String?, name: String): WatchLink {
        leave()
        displayName = name
        val key = WatchLink.newKey()
        val room = WatchLink.newRoomId()
        val link = WatchLink(
            room = room, key = key, mode = mode,
            host = if (mode == WatchLink.Mode.LAN) (lanHost ?: "") else "",
            port = if (mode == WatchLink.Mode.LAN) WatchLink.DEFAULT_LAN_PORT else 0,
            broker = if (mode == WatchLink.Mode.RELAY) broker else "",
            media = media, hostName = name,
        )
        mediaId = media.mediaId
        val crypto = SyncCrypto(key)
        val t = if (mode == WatchLink.Mode.LAN) LanHostTransport(link.port, crypto, selfId) else MqttTransport(link.broker, room, crypto, selfId)
        startTransport(t, Role.HOST, link)
        handler.post(hostTick)
        return link
    }

    fun join(link: WatchLink, name: String) {
        leave()
        displayName = name
        mediaId = link.media.mediaId
        val crypto = SyncCrypto(link.key)
        val t = if (link.mode == WatchLink.Mode.LAN) LanClientTransport(link.host, link.port, crypto, selfId) else MqttTransport(link.broker.ifBlank { WatchLink.DEFAULT_BROKER }, link.room, crypto, selfId)
        startTransport(t, Role.VIEWER, link)
        handler.post(viewerTick)
    }

    private fun startTransport(t: SyncTransport, role: Role, link: WatchLink) {
        transport = t
        _state.value = State(role = role, link = link, status = "Starting…")
        t.onStatus = { s ->
            handler.post {
                val connected = t.isConnected
                _state.value = _state.value.copy(status = s, connected = connected)
                if (connected && role == Role.VIEWER && s.startsWith("Connected")) {
                    send(SyncMessage(SyncMessage.Type.HELLO, selfId, now(), name = displayName))
                    sendPing()
                }
            }
        }
        t.onMessage = { m -> handler.post { onMessage(m) } }
        t.start()
    }

    fun leave() {
        handler.removeCallbacks(hostTick)
        handler.removeCallbacks(viewerTick)
        transport?.let { t ->
            runCatching { t.send(SyncMessage(SyncMessage.Type.BYE, selfId, now(), name = displayName)) }
            handler.postDelayed({ runCatching { t.stop() } }, 300)
        }
        transport = null
        lastHostState = null
        knownPeers.clear()
        if (_state.value.role != Role.NONE) resetRate()
        _state.value = State()
    }

    fun attachPlayer(p: Player) {
        player = p
        p.addListener(playerListener)
    }

    fun detachPlayer(p: Player) {
        p.removeListener(playerListener)
        if (player === p) player = null
    }

    /** Media the host switched to (zapping while hosting): tells viewers to load it. */
    fun hostMediaChanged(media: MediaRef) {
        if (_state.value.role != Role.HOST) return
        mediaId = media.mediaId
        _state.value = _state.value.copy(link = _state.value.link?.copy(media = media))
        val p = player ?: return
        send(SyncMessage(SyncMessage.Type.CMD, selfId, now(), cmd = SyncMessage.Cmd.LOAD, mediaId = mediaId, text = media.url, name = media.name, posMs = p.currentPosition, playing = p.playWhenReady))
    }

    // ------------------------------------------------------------ messaging

    private fun send(m: SyncMessage) { transport?.send(m) }

    private fun now() = System.currentTimeMillis()

    private fun sendPing() = send(SyncMessage(SyncMessage.Type.PING, selfId, now(), t0 = now()))

    private fun broadcastState() {
        val p = player ?: return
        if (_state.value.role != Role.HOST) return
        send(
            SyncMessage(
                SyncMessage.Type.STATE, selfId, now(), name = displayName,
                playing = p.playWhenReady && p.playbackState != Player.STATE_ENDED,
                posMs = p.currentPosition.coerceAtLeast(0), rate = p.playbackParameters.speed, mediaId = mediaId, isHost = true,
            ),
        )
    }

    private fun onMessage(m: SyncMessage) {
        val role = _state.value.role
        if (role == Role.NONE) return
        if (m.type != SyncMessage.Type.BYE) {
            knownPeers[m.from] = now()
            knownPeers.entries.removeAll { now() - it.value > 30_000 }
            _state.value = _state.value.copy(peers = knownPeers.size, lastRemoteName = m.name.ifBlank { _state.value.lastRemoteName })
        }
        when (m.type) {
            SyncMessage.Type.HELLO -> {
                onEvent("${m.name.ifBlank { "Someone" }} joined")
                if (role == Role.HOST) broadcastState()
            }
            SyncMessage.Type.BYE -> {
                knownPeers.remove(m.from)
                _state.value = _state.value.copy(peers = knownPeers.size)
                onEvent("${m.name.ifBlank { "Someone" }} left")
            }
            SyncMessage.Type.PING -> send(SyncMessage(SyncMessage.Type.PONG, selfId, now(), t0 = m.t0, t1 = now(), isHost = role == Role.HOST))
            SyncMessage.Type.PONG -> if (m.isHost || role == Role.VIEWER) {
                clock.addSample(m.t0, m.t1, now())
                _state.value = _state.value.copy(rttMs = clock.rttMs)
            }
            SyncMessage.Type.STATE -> if (role == Role.VIEWER) {
                lastHostState = m
                applyHostState(m)
            }
            SyncMessage.Type.CMD -> {
                val cmd = m.cmd ?: return
                if (role == Role.HOST) {
                    applyCommand(cmd, m)
                    onEvent("${m.name.ifBlank { "Viewer" }}: ${cmd.name.lowercase()}")
                    broadcastState()
                } else if (cmd == SyncMessage.Cmd.LOAD) {
                    // host changed media; tell UI
                    mediaId = m.mediaId
                    onLoadRequested(m.text, m.name)
                } else {
                    // another viewer's command, relayed by the host - the host STATE will follow
                }
            }
            SyncMessage.Type.CHAT -> onEvent("${m.name}: ${m.text}")
        }
    }

    /** Set by the player screen so a viewer can switch media when the host zaps. */
    var onLoadRequested: (url: String, name: String) -> Unit = { _, _ -> }

    private fun applyCommand(cmd: SyncMessage.Cmd, m: SyncMessage) {
        val p = player ?: return
        applyingRemote = true
        try {
            when (cmd) {
                SyncMessage.Cmd.PLAY -> { if (m.posMs > 0 && Math.abs(p.currentPosition - m.posMs) > 1500) p.seekTo(m.posMs); p.play() }
                SyncMessage.Cmd.PAUSE -> { p.pause(); if (m.posMs > 0 && Math.abs(p.currentPosition - m.posMs) > 1500) p.seekTo(m.posMs) }
                SyncMessage.Cmd.SEEK -> p.seekTo(m.posMs)
                SyncMessage.Cmd.LOAD -> {}
            }
        } finally {
            handler.post { applyingRemote = false }
        }
    }

    private fun applyHostState(m: SyncMessage) {
        val p = player ?: return
        if (m.mediaId.isNotEmpty() && mediaId.isNotEmpty() && m.mediaId != mediaId) return
        if (p.isCurrentMediaItemLive) {
            // Live streams: only mirror play/pause - positions are not comparable.
            syncPlayPause(p, m.playing)
            return
        }
        val target = SyncMath.expectedPosition(m, now(), clock.offsetMs)
        when (val c = SyncMath.correction(p.currentPosition, target, m.rate, m.playing)) {
            is SyncMath.Correction.Seek -> remote { p.seekTo(c.toMs); setRate(p, m.rate) }
            is SyncMath.Correction.Rate -> remote { setRate(p, c.rate) }
            SyncMath.Correction.None -> {}
        }
        syncPlayPause(p, m.playing)
    }

    private fun syncPlayPause(p: Player, playing: Boolean) {
        if (playing && !p.playWhenReady) remote { p.play() }
        if (!playing && p.playWhenReady) remote { p.pause() }
    }

    private fun setRate(p: Player, rate: Float) {
        if (Math.abs(lastRateSet - rate) > 0.001f) {
            lastRateSet = rate
            p.setPlaybackSpeed(rate)
        }
    }

    private fun resetRate() { player?.let { if (lastRateSet != 1f) { lastRateSet = 1f; it.setPlaybackSpeed(1f) } } }

    private inline fun remote(block: () -> Unit) {
        applyingRemote = true
        try { block() } finally { handler.post { applyingRemote = false } }
    }

    private val hostTick = object : Runnable {
        override fun run() {
            if (_state.value.role != Role.HOST) return
            broadcastState()
            handler.postDelayed(this, 2000)
        }
    }

    private var pingCounter = 0
    private val viewerTick = object : Runnable {
        override fun run() {
            if (_state.value.role != Role.VIEWER) return
            lastHostState?.let { if (now() - it.ts < 15_000) applyHostState(it) }
            if (pingCounter++ % 5 == 0) sendPing()
            handler.postDelayed(this, 1000)
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (applyingRemote) return
            if (reason != Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) return
            localAction(if (playWhenReady) SyncMessage.Cmd.PLAY else SyncMessage.Cmd.PAUSE)
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (applyingRemote) return
            if (reason == Player.DISCONTINUITY_REASON_SEEK) localAction(SyncMessage.Cmd.SEEK)
        }
    }

    private fun localAction(cmd: SyncMessage.Cmd) {
        val p = player ?: return
        when (_state.value.role) {
            Role.HOST -> broadcastState()
            Role.VIEWER -> send(SyncMessage(SyncMessage.Type.CMD, selfId, now(), name = displayName, cmd = cmd, posMs = p.currentPosition, playing = p.playWhenReady, mediaId = mediaId))
            Role.NONE -> {}
        }
        Log.d(TAG, "local $cmd")
    }
}
