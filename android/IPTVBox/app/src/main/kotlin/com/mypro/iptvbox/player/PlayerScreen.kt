package com.mypro.iptvbox.player

import com.mypro.iptvbox.ui.tvFocus
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.mypro.iptvbox.IptvApp
import com.mypro.iptvbox.core.model.Channel
import com.mypro.iptvbox.core.model.Programme
import com.mypro.iptvbox.core.sync.MediaRef
import com.mypro.iptvbox.core.sync.WatchLink
import com.mypro.iptvbox.core.util.TextUtil
import com.mypro.iptvbox.data.QualityCap
import com.mypro.iptvbox.ui.FocusableRow
import com.mypro.iptvbox.ui.LocalIsTv
import com.mypro.iptvbox.ui.panelColor
import com.mypro.iptvbox.util.QrCode
import com.mypro.iptvbox.watch.WatchSession
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
private fun fmtTime(ms: Long) = timeFmt.format(Date(ms))

/** Modifier for touch-only controls so they never steal D-pad focus from the remote handling. */
private fun Modifier.touchOnly() = this.focusProperties { canFocus = false }

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(ui: PlayerUiState, player: ExoPlayer, actions: PlayerActions) {
    val settings by IptvApp.settings.state.collectAsState()
    val watch by WatchSession.state.collectAsState()

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    setKeepContentOnPlayerReset(false)
                    this.player = player
                }
            },
            update = { view ->
                view.resizeMode = ui.resizeMode
                if (view.player !== player) view.player = player
            },
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier.fillMaxSize().pointerInput(Unit) {
                detectTapGestures(onTap = { ui.overlay = !ui.overlay }, onDoubleTap = { actions.togglePlay() })
            },
        )

        LaunchedEffect(Unit) {
            while (true) {
                ui.position = player.currentPosition.coerceAtLeast(0)
                ui.duration = player.duration.takeIf { it > 0 } ?: 0L
                ui.bufferedPosition = player.bufferedPosition.coerceAtLeast(0)
                delay(500)
            }
        }
        LaunchedEffect(ui.overlay, ui.playing, ui.anyPanel, ui.channel, ui.title) {
            if (ui.overlay && ui.playing && !ui.anyPanel) {
                delay(settings.overlayTimeoutSec * 1000L)
                ui.overlay = false
            }
        }

        if (ui.buffering && ui.error == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center).size(56.dp), color = MaterialTheme.colorScheme.primary)
        }

        AnimatedVisibility(visible = ui.overlay && !ui.anyPanel, enter = fadeIn(), exit = fadeOut()) {
            InfoOverlay(ui, actions, showNumbers = settings.showChannelNumbers)
        }

        if (ui.numberInput.isNotEmpty()) {
            Text(
                ui.numberInput,
                fontSize = 64.sp, fontWeight = FontWeight.Bold, color = Color.White,
                modifier = Modifier.align(Alignment.TopEnd).padding(32.dp).background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp)).padding(horizontal = 24.dp, vertical = 8.dp),
            )
        }

        if (watch.role != WatchSession.Role.NONE) {
            val label = buildString {
                append(if (watch.role == WatchSession.Role.HOST) "Hosting" else "Watching with ${watch.lastRemoteName.ifBlank { "host" }}")
                append(" · ")
                append(if (watch.peers > 0) "${watch.peers} connected" else watch.status.ifBlank { "connecting…" })
                if (watch.rttMs > 0) append(" · ${watch.rttMs} ms")
            }
            Surface(
                color = Color.Black.copy(alpha = 0.6f), shape = RoundedCornerShape(20.dp),
                modifier = Modifier.align(Alignment.TopCenter).padding(top = if (LocalIsTv.current) 27.dp else 12.dp),
            ) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Groups, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(label, style = MaterialTheme.typography.labelMedium, color = Color.White)
                }
            }
        }

        if (ui.toast.isNotEmpty()) {
            Surface(
                color = Color.Black.copy(alpha = 0.75f), shape = RoundedCornerShape(10.dp),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp),
            ) { Text(ui.toast, Modifier.padding(horizontal = 18.dp, vertical = 10.dp), color = Color.White) }
        }

        // BACK: clear a typed number, then hide the info bar, then (in a watch party) confirm before leaving.
        BackHandler(enabled = !ui.anyPanel && (ui.numberInput.isNotEmpty() || ui.overlay || watch.role != WatchSession.Role.NONE)) {
            when {
                ui.numberInput.isNotEmpty() -> ui.numberInput = ""
                ui.overlay -> ui.overlay = false
                else -> ui.confirmLeave = true
            }
        }
        if (ui.confirmLeave) {
            AlertDialog(
                onDismissRequest = { ui.confirmLeave = false },
                title = { Text(if (watch.role == WatchSession.Role.HOST) "End the watch party?" else "Leave the watch party?") },
                text = { Text(if (watch.role == WatchSession.Role.HOST) "Closing the player stops syncing for everyone who joined." else "You will stop watching in sync with ${watch.lastRemoteName.ifBlank { "the host" }}.") },
                confirmButton = { Button(onClick = { ui.confirmLeave = false; actions.exit() }, modifier = Modifier.tvFocus()) { Text("Leave") } },
                dismissButton = { TextButton(onClick = { ui.confirmLeave = false }, modifier = Modifier.tvFocus()) { Text("Stay") } },
            )
        }

        if (ui.channelList) ChannelListPanel(ui, actions)
        if (ui.epgPanel) ui.channel?.let { EpgPanel(it, actions) { ui.epgPanel = false } }
        if (ui.menu) MenuPanel(ui, actions, watch)
        ui.watchDialog?.let { InviteDialog(it, watch) { ui.watchDialog = null } }
        if (ui.hostPicker) HostPickerDialog(ui, actions)
        ui.error?.let { ErrorDialog(it, ui, actions) }
    }
}

@Composable
private fun InfoOverlay(ui: PlayerUiState, actions: PlayerActions, showNumbers: Boolean) {
    Box(Modifier.fillMaxSize()) {
        // Top: channel / title / EPG
        Column(
            Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.85f), Color.Transparent)))
                .padding(horizontal = if (LocalIsTv.current) 48.dp else 28.dp, vertical = if (LocalIsTv.current) 27.dp else 20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (ui.logo.isNotBlank()) {
                    AsyncImage(model = ui.logo, contentDescription = null, modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.08f)))
                    Spacer(Modifier.width(16.dp))
                }
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (showNumbers && ui.number > 0) {
                            Text("${ui.number}", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(14.dp))
                        }
                        Text(ui.title, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (ui.isFavorite) { Spacer(Modifier.width(10.dp)); Icon(Icons.Default.Star, null, tint = MaterialTheme.colorScheme.secondary) }
                    }
                    if (ui.groupName.isNotBlank()) Text(ui.groupName, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodyMedium)
                    ui.nowProgramme?.let { p ->
                        Spacer(Modifier.height(6.dp))
                        Text("${fmtTime(p.start)} – ${fmtTime(p.stop)}   ${p.title}", color = Color.White, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val now = System.currentTimeMillis()
                        val progress = ((now - p.start).toFloat() / p.durationMs.coerceAtLeast(1)).coerceIn(0f, 1f)
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(0.5f).padding(top = 6.dp).height(4.dp), color = MaterialTheme.colorScheme.primary, trackColor = Color.White.copy(alpha = 0.2f))
                    }
                    ui.nextProgramme?.let { p ->
                        Text("Next: ${fmtTime(p.start)}  ${p.title}", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(fmtTime(System.currentTimeMillis()), fontSize = 24.sp, color = Color.White)
                    if (ui.videoInfo.isNotBlank()) Text(ui.videoInfo, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium)
                    if (ui.speed != 1f) Text("${ui.speed}×", color = MaterialTheme.colorScheme.secondary)
                }
            }
        }

        // Bottom: transport controls (touch) + remote hints
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
                .padding(horizontal = if (LocalIsTv.current) 48.dp else 28.dp, vertical = if (LocalIsTv.current) 27.dp else 16.dp),
        ) {
            if (!ui.isLive && ui.duration > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(TextUtil.formatDuration(ui.position), color = Color.White, style = MaterialTheme.typography.labelLarge)
                    Slider(
                        value = ui.position.toFloat().coerceIn(0f, ui.duration.toFloat()),
                        onValueChange = { actions.seekTo(it.toLong()) },
                        valueRange = 0f..ui.duration.toFloat(),
                        modifier = Modifier.weight(1f).padding(horizontal = 12.dp).touchOnly(),
                    )
                    Text(TextUtil.formatDuration(ui.duration), color = Color.White, style = MaterialTheme.typography.labelLarge)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (ui.channel != null) {
                    IconButton(onClick = { actions.zap(-1) }, modifier = Modifier.touchOnly()) { Icon(Icons.Default.SkipPrevious, "Previous channel", tint = Color.White) }
                }
                IconButton(onClick = { actions.togglePlay() }, modifier = Modifier.touchOnly()) {
                    Icon(if (ui.playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Play/pause", tint = Color.White, modifier = Modifier.size(32.dp))
                }
                if (ui.channel != null) {
                    IconButton(onClick = { actions.zap(1) }, modifier = Modifier.touchOnly()) { Icon(Icons.Default.SkipNext, "Next channel", tint = Color.White) }
                    IconButton(onClick = { ui.channelList = true }, modifier = Modifier.touchOnly()) { Icon(Icons.Default.List, "Channels", tint = Color.White) }
                    IconButton(onClick = { ui.epgPanel = true }, modifier = Modifier.touchOnly()) { Icon(Icons.Default.CalendarMonth, "Guide", tint = Color.White) }
                } else if (!ui.isLive) {
                    IconButton(onClick = { actions.seekBy(-10_000) }, modifier = Modifier.touchOnly()) { Icon(Icons.Default.Replay10, "Back 10s", tint = Color.White) }
                    IconButton(onClick = { actions.seekBy(10_000) }, modifier = Modifier.touchOnly()) { Icon(Icons.Default.Forward10, "Forward 10s", tint = Color.White) }
                }
                IconButton(onClick = { ui.menu = true }, modifier = Modifier.touchOnly()) { Icon(Icons.Default.Tune, "Options", tint = Color.White) }
                IconButton(onClick = { actions.showHostPicker() }, modifier = Modifier.touchOnly()) { Icon(Icons.Default.Groups, "Watch together", tint = Color.White) }
                Spacer(Modifier.weight(1f))
                Text(
                    if (ui.channel != null) "OK: channels   ◀ ▶: guide   ▲ ▼: zap   0-9: number   Hold OK: options"
                    else if (ui.isLive) "OK: play/pause   Hold OK: options" else "OK: play/pause   ◀ ▶: seek 10 s   Hold OK: options",
                    color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

@Composable
private fun SidePanel(alignEnd: Boolean, width: Int, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    BackHandler(onBack = onDismiss)
    Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { onDismiss() }) }) {
        Surface(
            color = panelColor(),
            contentColor = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxHeight().width(width.dp).align(if (alignEnd) Alignment.CenterEnd else Alignment.CenterStart)
                .pointerInput(Unit) { detectTapGestures(onTap = {}) },
        ) {
            Column(Modifier.fillMaxSize().padding(12.dp), content = content)
        }
    }
}

@Composable
private fun ChannelListPanel(ui: PlayerUiState, actions: PlayerActions) {
    val settingsState by IptvApp.settings.state.collectAsState()
    val groups = remember(settingsState.groupMode) { actions.groups() }
    var groupIndex by remember { mutableIntStateOf(groups.indexOf(ui.groupName).takeIf { it >= 0 } ?: 0) }
    LaunchedEffect(groups) { if (groupIndex >= groups.size) groupIndex = 0 }
    val group = groups[groupIndex.coerceIn(0, groups.size - 1)]
    val channels = remember(group, settingsState.groupMode, settingsState.sortMode) { actions.channelsInGroup(group) }
    val epg by IptvApp.repo.epg.collectAsState()
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    val currentIdx = channels.indexOfFirst { it.id == ui.channel?.id }

    LaunchedEffect(group) {
        val idx = if (currentIdx >= 0) currentIdx else 0
        if (channels.isNotEmpty()) listState.scrollToItem(idx)
        runCatching { focusRequester.requestFocus() }
    }

    SidePanel(alignEnd = false, width = 440, onDismiss = { ui.channelList = false }) {
        Row(
            Modifier.fillMaxWidth().onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionLeft -> { groupIndex = (groupIndex - 1 + groups.size) % groups.size; true }
                    Key.DirectionRight -> { groupIndex = (groupIndex + 1) % groups.size; true }
                    else -> false
                }
            },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { groupIndex = (groupIndex - 1 + groups.size) % groups.size }, modifier = Modifier.touchOnly()) { Icon(Icons.Default.ChevronLeft, "Previous group") }
            Text(group, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            IconButton(onClick = { groupIndex = (groupIndex + 1) % groups.size }, modifier = Modifier.touchOnly()) { Icon(Icons.Default.ChevronRight, "Next group") }
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${channels.size} channels · ◀ ▶ switch group", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            FocusableRow(onClick = { actions.cycleGroupMode(); groupIndex = 0 }, modifier = Modifier.width(IntrinsicSize.Max), padding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                Icon(Icons.Default.Public, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(4.dp))
                Text(actions.groupModeLabel(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
        if (channels.isEmpty()) {
            Text("No channels in this group", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionLeft -> { groupIndex = (groupIndex - 1 + groups.size) % groups.size; true }
                    Key.DirectionRight -> { groupIndex = (groupIndex + 1) % groups.size; true }
                    else -> false
                }
            },
        ) {
            itemsIndexed(channels, key = { _, c -> c.id }) { idx, c ->
                val (now, _) = remember(c.id, epg) { epg.nowNext(c) }
                FocusableRow(
                    onClick = { ui.channelList = false; actions.selectChannel(c, group) },
                    selected = c.id == ui.channel?.id,
                    modifier = if (idx == (if (currentIdx >= 0) currentIdx else 0)) Modifier.focusRequester(focusRequester) else Modifier,
                ) {
                    Text("${c.number}", Modifier.width(44.dp), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    if (c.logo.isNotBlank()) {
                        AsyncImage(model = c.logo, contentDescription = null, modifier = Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)))
                    } else {
                        Box(Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)).background(Color.White.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) { Icon(Icons.Default.Tv, null, tint = Color.White.copy(alpha = 0.5f)) }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                        if (now != null) Text(now.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (c.catchupDays > 0) Icon(Icons.Default.History, "Catch-up", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
private fun EpgPanel(channel: Channel, actions: PlayerActions, onDismiss: () -> Unit) {
    var programmes by remember(channel.id) { mutableStateOf(actions.programmes(channel)) }
    var loading by remember(channel.id) { mutableStateOf(programmes.isEmpty()) }
    LaunchedEffect(channel.id) {
        if (programmes.isEmpty()) {
            programmes = actions.shortEpg(channel)
            loading = false
        }
    }
    val now = System.currentTimeMillis()
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    val liveIdx = programmes.indexOfFirst { it.isLiveAt(now) }
    LaunchedEffect(programmes) {
        if (liveIdx >= 0) listState.scrollToItem((liveIdx - 1).coerceAtLeast(0))
        runCatching { focusRequester.requestFocus() }
    }
    SidePanel(alignEnd = true, width = 520, onDismiss = onDismiss) {
        Text(channel.name, style = MaterialTheme.typography.titleMedium)
        Text(
            if (channel.catchupDays > 0) "Programme guide · catch-up available (${channel.catchupDays} day${if (channel.catchupDays > 1) "s" else ""})" else "Programme guide",
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp),
        )
        when {
            loading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            programmes.isEmpty() -> Text("No guide data for this channel.\nAdd an EPG URL to the playlist to see programmes.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                itemsIndexed(programmes) { idx, p ->
                    val isLive = p.isLiveAt(now)
                    val past = p.stop <= now
                    val canCatchup = past && channel.catchupDays > 0 && now - p.start < channel.catchupDays * 86_400_000L
                    FocusableRow(
                        onClick = { if (canCatchup) actions.playCatchup(channel, p) else if (isLive) onDismiss() },
                        selected = isLive,
                        modifier = if (idx == liveIdx.coerceAtLeast(0)) Modifier.focusRequester(focusRequester) else Modifier,
                    ) {
                        Column(Modifier.width(96.dp)) {
                            Text(fmtTime(p.start), style = MaterialTheme.typography.bodyMedium, color = if (past && !isLive) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                            Text(fmtTime(p.stop), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(p.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, color = if (past && !isLive && !canCatchup) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                            if (p.description.isNotBlank()) Text(p.description, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (isLive) Badge(containerColor = MaterialTheme.colorScheme.error) { Text("LIVE") }
                        else if (canCatchup) Icon(Icons.Default.Replay, "Play from start", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuPanel(ui: PlayerUiState, actions: PlayerActions, watch: WatchSession.State) {
    var sub by remember { mutableStateOf(ui.menuInitialSub) }
    LaunchedEffect(Unit) { ui.menuInitialSub = null }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(sub) { runCatching { focusRequester.requestFocus() } }
    SidePanel(alignEnd = true, width = 420, onDismiss = { if (sub != null) sub = null else ui.menu = false }) {
        Text(sub ?: "Options", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            when (sub) {
                null -> {
                    if (ui.channel != null) item {
                        MenuItem(if (ui.isFavorite) Icons.Default.Star else Icons.Default.StarBorder, if (ui.isFavorite) "Remove from favourites" else "Add to favourites", Modifier.focusRequester(focusRequester)) { actions.toggleFavorite() }
                    }
                    item {
                        val label = when (ui.resizeMode) {
                            AspectRatioFrameLayout.RESIZE_MODE_FIT -> "Fit"; AspectRatioFrameLayout.RESIZE_MODE_FILL -> "Stretch"
                            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "Zoom / crop"; AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH -> "Fixed width"; else -> "Fixed height"
                        }
                        MenuItem(Icons.Default.AspectRatio, "Aspect ratio: $label", if (ui.channel == null) Modifier.focusRequester(focusRequester) else Modifier) {
                            val order = listOf(AspectRatioFrameLayout.RESIZE_MODE_FIT, AspectRatioFrameLayout.RESIZE_MODE_ZOOM, AspectRatioFrameLayout.RESIZE_MODE_FILL, AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH, AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT)
                            actions.setResizeMode(order[(order.indexOf(ui.resizeMode) + 1) % order.size])
                        }
                    }
                    item { MenuItem(Icons.Default.HighQuality, "Video quality: ${ui.quality}") { sub = "Video quality" } }
                    item { MenuItem(Icons.Default.Audiotrack, "Audio track") { sub = "Audio track" } }
                    item { MenuItem(Icons.Default.Subtitles, "Subtitles") { sub = "Subtitles" } }
                    if (!ui.isLive) item { MenuItem(Icons.Default.Speed, "Playback speed: ${ui.speed}×") { sub = "Playback speed" } }
                    item { HorizontalDivider(Modifier.padding(vertical = 6.dp)) }
                    if (watch.role == WatchSession.Role.NONE) {
                        item { MenuItem(Icons.Default.Groups, "Watch together…") { actions.showHostPicker() } }
                    } else {
                        if (watch.role == WatchSession.Role.HOST) item { MenuItem(Icons.Default.Share, "Show invitation") { actions.showInvite() } }
                        item { MenuItem(Icons.Default.ExitToApp, "Leave watch party") { actions.leaveWatch() } }
                    }
                    item { HorizontalDivider(Modifier.padding(vertical = 6.dp)) }
                    item { MenuItem(Icons.Default.OpenInNew, "Open in another player") { actions.openExternal(); ui.menu = false } }
                    if (ui.videoInfo.isNotBlank()) item { MenuItem(Icons.Default.Info, ui.videoInfo) {} }
                    item { MenuItem(Icons.Default.Close, "Stop and exit") { actions.exit() } }
                }
                "Video quality" -> {
                    val tracks = actions.videoTracks()
                    item { Text("Preset", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp)) }
                    itemsIndexed(QualityCap.entries) { i, cap ->
                        MenuItem(if (ui.quality == cap.label) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked, cap.label, if (i == 0) Modifier.focusRequester(focusRequester) else Modifier) { actions.setQualityCap(cap); sub = null }
                    }
                    if (tracks.size > 1) {
                        item { Text("Streams offered by this channel", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 4.dp)) }
                        items(tracks.sortedByDescending { it.group.getTrackFormat(it.index).height }) { t ->
                            MenuItem(if (ui.quality == t.label) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked, t.label) { actions.selectVideoTrack(t); sub = null }
                        }
                    } else {
                        item { Text(if (tracks.isEmpty()) "No video track information yet." else "This stream has a single quality; presets apply to streams that offer several.", Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
                "Audio track" -> {
                    val tracks = actions.audioTracks()
                    if (tracks.isEmpty()) item { Text("No selectable audio tracks", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    itemsIndexed(tracks) { i, t ->
                        MenuItem(if (t.selected) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked, t.label, if (i == 0) Modifier.focusRequester(focusRequester) else Modifier) { actions.selectTrack(t); sub = null }
                    }
                }
                "Subtitles" -> {
                    val tracks = actions.subtitleTracks()
                    item { MenuItem(if (tracks.none { it.selected }) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked, "Off", Modifier.focusRequester(focusRequester)) { actions.selectTrack(null); sub = null } }
                    items(tracks) { t -> MenuItem(if (t.selected) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked, t.label) { actions.selectTrack(t); sub = null } }
                    if (tracks.isEmpty()) item { Text("This stream has no subtitle tracks", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                "Playback speed" -> {
                    val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
                    itemsIndexed(speeds) { i, s ->
                        MenuItem(if (s == ui.speed) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked, "$s×", if (i == 2) Modifier.focusRequester(focusRequester) else Modifier) { actions.setSpeed(s); sub = null }
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FocusableRow(onClick = onClick, modifier = modifier) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(14.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun HostPickerDialog(ui: PlayerUiState, actions: PlayerActions) {
    AlertDialog(
        onDismissRequest = { ui.hostPicker = false },
        title = { Text("Watch together") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Start a party for what is playing now. Everyone who opens the invitation plays the same thing in sync; anyone can pause, play or seek.")
                Text("For a local video file, the other person needs the same file on their device.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { actions.startHosting(WatchLink.Mode.RELAY) }, modifier = Modifier.tvFocus().fillMaxWidth()) { Icon(Icons.Default.Public, null); Spacer(Modifier.width(8.dp)); Text("Over the internet") }
                OutlinedButton(onClick = { actions.startHosting(WatchLink.Mode.LAN) }, modifier = Modifier.tvFocus().fillMaxWidth()) { Icon(Icons.Default.Wifi, null); Spacer(Modifier.width(8.dp)); Text("Same Wi-Fi only (no internet needed)") }
                TextButton(onClick = { ui.hostPicker = false }, modifier = Modifier.tvFocus().fillMaxWidth()) { Text("Cancel") }
            }
        },
    )
}

@Composable
fun InviteDialog(link: WatchLink, watch: WatchSession.State, onDismiss: () -> Unit) {
    val uri = remember(link) { link.toUri() }
    val qr = remember(uri) { QrCode.render(uri, 480)?.asImageBitmap() }
    val context = androidx.compose.ui.platform.LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Invite someone") },
        text = {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                qr?.let { Image(it, contentDescription = "QR code", modifier = Modifier.size(200.dp).background(Color.White).padding(6.dp)) }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Room ${link.shortCode()} · ${if (link.mode == WatchLink.Mode.LAN) "same Wi-Fi (${link.host})" else "internet relay"}", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (link.media.kind == MediaRef.Kind.FILE) "Playing a local file: ${link.media.name}. The other person needs the same file." else "Playing: ${link.media.name.ifBlank { link.media.url }}",
                        style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    )
                    Text("Send this link; opening it on another phone, tablet or TV box with IPTV Box installed joins the party. It can also be pasted under Watch Together.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (link.media.kind == MediaRef.Kind.URL && Regex("""(?i)(username=|password=|/(live|movie|series|timeshift)/[^/]+/[^/]+/)""").containsMatchIn(link.media.url)) {
                        Text(
                            "⚠ This link contains your IPTV account login. Share it only with people you trust; accounts limited to one connection may disconnect one of you.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(uri, style = MaterialTheme.typography.labelMedium, maxLines = 3, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.primary)
                    }
                    Text(watch.status.ifBlank { "Waiting…" } + if (watch.peers > 0) " · ${watch.peers} connected" else "", style = MaterialTheme.typography.labelMedium)
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(modifier = Modifier.tvFocus(), onClick = {
                    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("IPTV Box invitation", uri))
                }) { Icon(Icons.Default.ContentCopy, null); Spacer(Modifier.width(6.dp)); Text("Copy") }
                Button(modifier = Modifier.tvFocus(), onClick = {
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(android.content.Intent.EXTRA_TEXT, "Watch with me on IPTV Box:\n$uri")
                    runCatching { context.startActivity(android.content.Intent.createChooser(send, "Share invitation")) }
                }) { Icon(Icons.Default.Share, null); Spacer(Modifier.width(6.dp)); Text("Share") }
                TextButton(modifier = Modifier.tvFocus(), onClick = onDismiss) { Text("Close") }
            }
        },
    )
}

@Composable
private fun ErrorDialog(message: String, ui: PlayerUiState, actions: PlayerActions) {
    AlertDialog(
        onDismissRequest = { ui.error = null },
        icon = { Icon(Icons.Default.ErrorOutline, null, tint = MaterialTheme.colorScheme.error) },
        title = { Text("Playback problem") },
        text = { Text(message) },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(modifier = Modifier.tvFocus(), onClick = { actions.retry() }) { Text("Retry") }
                if (ui.channel != null) OutlinedButton(modifier = Modifier.tvFocus(), onClick = { ui.error = null; actions.zap(1) }) { Text("Next channel") }
                TextButton(modifier = Modifier.tvFocus(), onClick = { actions.exit() }) { Text("Close") }
            }
        },
    )
}
