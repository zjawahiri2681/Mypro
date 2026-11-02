package com.mypro.familytalk.ui

import android.app.PictureInPictureParams
import android.content.Intent
import android.os.Bundle
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMerge
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.mypro.familytalk.FamilyTalkApp
import com.mypro.familytalk.call.CallUi
import com.mypro.familytalk.core.call.CallPhase
import com.mypro.familytalk.core.call.Mesh
import com.mypro.familytalk.service.Notifications
import com.mypro.familytalk.sip.SipUi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

class CallActivity : ComponentActivity() {
    private val app get() = FamilyTalkApp.instance

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (app.family.data.value.secureScreen) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        handleAccept(intent)
        // Close when neither a family call nor a SIP call is left.
        lifecycleScope.launch {
            var seen = false
            combine(app.calls.ui, app.sip.ui) { c, s -> c != null || s.calls.isNotEmpty() }.collect { active ->
                if (active) seen = true else if (seen) finish()
            }
        }
        lifecycleScope.launch { delay(4000); if (app.calls.ui.value == null && app.sip.ui.value.calls.isEmpty()) finish() }
        setContent {
            FamilyTalkTheme(dark = true) {
                val call by app.calls.ui.collectAsStateWithLifecycle()
                val sip by app.sip.ui.collectAsStateWithLifecycle()
                Box(Modifier.fillMaxSize().background(Brand.CallBg)) {
                    when {
                        call != null -> FamilyCall(app, call!!)
                        sip.calls.isNotEmpty() -> SipCalls(app, sip)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleAccept(intent) }

    private fun handleAccept(intent: Intent?) {
        val what = intent?.getStringExtra(EXTRA_ACCEPT) ?: return
        intent.removeExtra(EXTRA_ACCEPT)
        Notifications.cancelIncoming(this)
        if (what == "family") app.calls.accept() else if (what.startsWith("sip:")) app.sip.accept(what.removePrefix("sip:"))
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val c = app.calls.ui.value
        if (c != null && c.video && c.phase == CallPhase.ACTIVE) {
            runCatching { enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(9, 16)).build()) }
        }
    }

    companion object { const val EXTRA_ACCEPT = "accept" }
}

@Composable
private fun FamilyCall(app: FamilyTalkApp, c: CallUi) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var addPicker by remember { mutableStateOf(false) }
    LaunchedEffect(c.connectedAt) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    val status = when (c.phase) {
        CallPhase.OUTGOING -> "Calling…"
        CallPhase.RINGING_OUT -> "Ringing…"
        CallPhase.INCOMING -> if (c.video) "Incoming video call" else "Incoming call"
        CallPhase.CONNECTING -> "Connecting securely…"
        CallPhase.ACTIVE -> c.connectedAt?.let { formatDuration((now - it) / 1000) } ?: "Connected"
        CallPhase.ENDED -> "Call ended" + (c.endReason?.takeIf { it != "hangup" }?.let { " · $it" } ?: "")
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
        // Header: who, status, encryption state
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(c.title, color = Color.White, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
            Text(status, color = Color.White.copy(alpha = 0.8f), fontSize = 16.sp)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                Icon(Icons.Filled.Lock, null, tint = Brand.TealLight, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text(if (c.echoTest) "Local test" else "End-to-end encrypted", color = Brand.TealLight, fontSize = 13.sp)
                if (!c.echoTest) { Spacer(Modifier.width(10.dp)); TrustBadge(c.verified) }
            }
        }
        Spacer(Modifier.height(16.dp))
        // Body: video tiles or avatars
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val showVideo = c.video && c.phase in setOf(CallPhase.CONNECTING, CallPhase.ACTIVE)
            if (showVideo) {
                LazyVerticalGrid(GridCells.Fixed(if (c.peers.size > 1) 2 else 1), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(c.peers, key = { it.memberId }) { p ->
                        Box(Modifier.fillMaxWidth().aspectRatio(if (c.peers.size > 1) 0.75f else 0.6f).clip(RoundedCornerShape(16.dp)).background(Color(0xFF13201E))) {
                            if (p.video != null && p.camOn) VideoView(app, p.video, mirror = false)
                            else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Avatar(p.name, 0, 72.dp) }
                            Text(p.name + if (!p.micOn) " · muted" else "", color = Color.White, fontSize = 13.sp,
                                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp).background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
                        }
                    }
                }
                app.calls.rtc.videoTrack?.takeIf { c.camera }?.let { local ->
                    Box(Modifier.align(Alignment.BottomEnd).size(110.dp, 160.dp).clip(RoundedCornerShape(12.dp))) { VideoView(app, local, mirror = app.calls.rtc.frontCamera) }
                }
            } else {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Row(horizontalArrangement = Arrangement.spacedBy((-12).dp)) { c.peers.take(4).forEach { Avatar(it.name, it.memberId.hashCode(), if (c.peers.size == 1) 120.dp else 72.dp) } }
                    if (c.conference) {
                        Spacer(Modifier.height(16.dp))
                        c.peers.forEach { p -> Text("${p.name} · ${p.state}" + if (!p.micOn) " · muted" else "", color = Color.White.copy(alpha = 0.85f)) }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        // Controls
        if (c.phase == CallPhase.INCOMING) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                LabeledAction(Icons.Filled.CallEnd, "Decline", Brand.Decline) { app.calls.decline() }
                LabeledAction(if (c.video) Icons.Filled.Videocam else Icons.Filled.Call, "Accept", Brand.Accept) { app.calls.accept() }
            }
        } else if (c.phase != CallPhase.ENDED) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                val dim = Color.White.copy(alpha = 0.16f)
                LabeledAction(if (c.mic) Icons.Filled.Mic else Icons.Filled.MicOff, if (c.mic) "Mute" else "Unmute", if (c.mic) dim else Color.White, small = true,
                    content = if (c.mic) Color.White else Color.Black) { app.calls.toggleMic() }
                LabeledAction(Icons.AutoMirrored.Filled.VolumeUp, "Speaker", if (c.speaker) Color.White else dim, small = true,
                    content = if (c.speaker) Color.Black else Color.White) { app.calls.toggleSpeaker() }
                if (c.video) {
                    LabeledAction(if (c.camera) Icons.Filled.Videocam else Icons.Filled.VideocamOff, "Camera", if (c.camera) dim else Color.White, small = true,
                        content = if (c.camera) Color.White else Color.Black) { app.calls.toggleCamera() }
                    LabeledAction(Icons.Filled.Cameraswitch, "Flip", dim, small = true) { app.calls.switchCamera() }
                }
                if (!c.echoTest && Mesh.canAdd(c.peers.size + 1, c.video)) LabeledAction(Icons.Filled.PersonAdd, "Add", dim, small = true) { addPicker = true }
            }
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                LabeledAction(Icons.Filled.CallEnd, "End", Brand.Decline) { app.calls.hangup() }
            }
        }
    }
    if (addPicker) {
        val roster = app.family.roster.value
        val candidates = roster?.members?.filter { m -> m.id != app.family.myId && c.peers.none { it.memberId == m.id } }.orEmpty()
        AlertDialog(
            onDismissRequest = { addPicker = false },
            title = { Text("Add to call") },
            text = {
                Column {
                    if (candidates.isEmpty()) Text("Everyone is already in the call.")
                    candidates.forEach { m ->
                        ListItem(
                            leadingContent = { Avatar(m.name, m.color, 36.dp) },
                            headlineContent = { Text(m.name) }, supportingContent = { Text("Ext ${m.ext}") },
                            modifier = Modifier.clickable { app.calls.addPerson(m); addPicker = false },
                        )
                    }
                    Text("Outside numbers can't join family calls directly: SIP calls are not end-to-end encrypted. See Settings › How you're protected.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { TextButton({ addPicker = false }) { Text("Close") } },
        )
    }
}

@Composable
private fun SipCalls(app: FamilyTalkApp, s: SipUi) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (s.conference) "SIP conference" else "SIP call", color = Color.White, style = MaterialTheme.typography.headlineMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.LockOpen, null, tint = Brand.SandLight, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(4.dp))
            Text("Not end-to-end encrypted (goes through your SIP provider)", color = Brand.SandLight, fontSize = 13.sp)
        }
        s.calls.forEach { call ->
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF13201E))) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(call.remote, call.remote.hashCode(), 44.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(call.remote, color = Color.White, style = MaterialTheme.typography.titleMedium)
                        Text(call.state + (call.connectedAt?.let { " · " + formatDuration((now - it) / 1000) } ?: "") + if (call.encrypted) " · SRTP" else " · unencrypted",
                            color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp)
                    }
                    if (call.state == "Incoming") {
                        RoundAction(Icons.Filled.CallEnd, "Decline", Brand.Decline, { app.sip.decline(call.key) }, 48.dp); Spacer(Modifier.width(8.dp))
                        RoundAction(Icons.Filled.Call, "Accept", Brand.Accept, { app.sip.accept(call.key) }, 48.dp)
                    } else {
                        RoundAction(if (call.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, if (call.paused) "Resume" else "Hold", Color.White.copy(alpha = 0.16f), { app.sip.togglePause(call.key) }, 44.dp)
                        Spacer(Modifier.width(8.dp))
                        RoundAction(Icons.Filled.CallEnd, "Hang up", Brand.Decline, { app.sip.hangup(call.key) }, 44.dp)
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            LabeledAction(if (s.micMuted) Icons.Filled.MicOff else Icons.Filled.Mic, if (s.micMuted) "Unmute" else "Mute", Color.White.copy(alpha = 0.16f), small = true) { app.sip.toggleMic() }
            if (s.calls.size >= 2 && !s.conference) LabeledAction(Icons.AutoMirrored.Filled.CallMerge, "Merge", Color.White.copy(alpha = 0.16f), small = true) { app.sip.mergeAll() }
            LabeledAction(Icons.Filled.CallEnd, "End all", Brand.Decline) { app.sip.hangupAll() }
        }
        Text("Tip: to add another number, go back to the keypad and dial it — then tap Merge.", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
    }
}

@Composable
private fun LabeledAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, color: Color, small: Boolean = false, content: Color = Color.White, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        RoundAction(icon, label, color, onClick, if (small) 56.dp else 72.dp, content)
        Text(label, color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun VideoView(app: FamilyTalkApp, track: VideoTrack, mirror: Boolean) {
    val egl = app.calls.rtc.egl
    var renderer by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            SurfaceViewRenderer(ctx).apply {
                init(egl.eglBaseContext, null)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                setEnableHardwareScaler(true)
                renderer = this
            }
        },
        update = { it.setMirror(mirror) },
    )
    DisposableEffect(track, renderer) {
        val r = renderer
        if (r != null) runCatching { track.addSink(r) }
        onDispose { if (r != null) runCatching { track.removeSink(r) } }
    }
    DisposableEffect(Unit) { onDispose { renderer?.release() } }
}
