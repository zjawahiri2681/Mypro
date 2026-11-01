package com.mypro.iptvbox.player

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.mypro.iptvbox.IptvApp
import com.mypro.iptvbox.core.model.Channel
import com.mypro.iptvbox.core.model.ContentType
import com.mypro.iptvbox.core.model.Programme
import com.mypro.iptvbox.core.net.Http
import com.mypro.iptvbox.core.sync.MediaRef
import com.mypro.iptvbox.core.sync.WatchLink
import com.mypro.iptvbox.core.util.ChannelOrganizer
import com.mypro.iptvbox.data.DecoderMode
import com.mypro.iptvbox.data.QualityCap
import com.mypro.iptvbox.ui.IptvTheme
import com.mypro.iptvbox.util.Files
import com.mypro.iptvbox.util.Net
import com.mypro.iptvbox.util.toHeaderMap
import com.mypro.iptvbox.watch.WatchSession
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {

    companion object {
        const val EXTRA_TYPE = "type"
        const val TYPE_CHANNEL = "channel"
        const val TYPE_URL = "url"
        const val TYPE_WATCH = "watch"
        const val EXTRA_CHANNEL_ID = "channelId"
        const val EXTRA_GROUP = "group"
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
        const val EXTRA_HEADERS = "headers"
        const val EXTRA_IS_LIVE = "isLive"
        const val EXTRA_WATCH_LINK = "watchLink"
        const val EXTRA_FILE_URI = "fileUri"

        fun playChannel(ctx: Context, channel: Channel, group: String? = null) {
            ctx.startActivity(
                Intent(ctx, PlayerActivity::class.java)
                    .putExtra(EXTRA_TYPE, TYPE_CHANNEL)
                    .putExtra(EXTRA_CHANNEL_ID, channel.id)
                    .putExtra(EXTRA_GROUP, group ?: channel.group),
            )
        }

        fun playUrl(ctx: Context, url: String, title: String, headers: String = "", isLive: Boolean = false) {
            ctx.startActivity(
                Intent(ctx, PlayerActivity::class.java)
                    .putExtra(EXTRA_TYPE, TYPE_URL)
                    .putExtra(EXTRA_URL, url)
                    .putExtra(EXTRA_TITLE, title)
                    .putExtra(EXTRA_HEADERS, headers)
                    .putExtra(EXTRA_IS_LIVE, isLive),
            )
        }

        fun joinWatch(ctx: Context, link: WatchLink, fileUri: Uri?) {
            ctx.startActivity(
                Intent(ctx, PlayerActivity::class.java)
                    .putExtra(EXTRA_TYPE, TYPE_WATCH)
                    .putExtra(EXTRA_WATCH_LINK, link.toUri())
                    .putExtra(EXTRA_FILE_URI, fileUri?.toString()),
            )
        }
    }

    private val repo get() = IptvApp.repo
    private val settings get() = IptvApp.settings
    private val ui = PlayerUiState()
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var player: ExoPlayer
    private lateinit var okHttpFactory: OkHttpDataSource.Factory
    private lateinit var trackSelector: DefaultTrackSelector

    private var zapList: List<Channel> = emptyList()
    private var zapIndex = -1
    private var currentUrl: String = ""
    private var currentHeaders: String = ""
    private var currentFileUri: Uri? = null
    private var retryCount = 0
    private var isInPip = false
    private val retryRunnable = Runnable { if (!isDestroyed) { player.prepare(); player.play() } }
    private val numberRunnable = Runnable { commitNumberInput() }
    private val toastRunnable = Runnable { ui.toast = "" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()
        player = buildPlayer()
        applyInitialQuality()
        WatchSession.attachPlayer(player)
        WatchSession.onEvent = { showToast(it) }
        WatchSession.onLoadRequested = { url, name ->
            if (WatchSession.state.value.role == WatchSession.Role.VIEWER && url.isNotBlank()) startUrl(url, name, "", isLive = false)
        }
        setContent {
            IptvTheme {
                PlayerScreen(ui = ui, player = player, actions = actions)
            }
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    // ------------------------------------------------------------------ intent → playback

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        when (intent.getStringExtra(EXTRA_TYPE)) {
            TYPE_CHANNEL -> {
                val id = intent.getStringExtra(EXTRA_CHANNEL_ID) ?: return
                val channel = repo.channelById(id) ?: run { ui.error = "Channel not found"; return }
                val requested = intent.getStringExtra(EXTRA_GROUP)
                val all = repo.content.value?.channels ?: emptyList()
                val key = when {
                    requested == "Favourites" || requested == "All channels" -> requested ?: ""
                    requested != null && ChannelOrganizer.of(all).groups(settings.current.groupMode).any { it.key == requested } -> requested
                    else -> ChannelOrganizer.of(all).keyOf(channel, settings.current.groupMode)
                }
                setZapList(key)
                startChannel(channel)
            }
            TYPE_URL -> {
                val url = intent.getStringExtra(EXTRA_URL) ?: return
                startUrl(url, intent.getStringExtra(EXTRA_TITLE) ?: url, intent.getStringExtra(EXTRA_HEADERS) ?: "", intent.getBooleanExtra(EXTRA_IS_LIVE, false))
            }
            TYPE_WATCH -> {
                val link = intent.getStringExtra(EXTRA_WATCH_LINK)?.let { WatchLink.parse(it) } ?: run { ui.error = "Invalid invitation link"; return }
                val fileUri = intent.getStringExtra(EXTRA_FILE_URI)?.let { Uri.parse(it) }
                WatchSession.join(link, settings.current.displayName)
                if (link.media.kind == MediaRef.Kind.FILE && fileUri != null) {
                    currentFileUri = fileUri
                    startUrl(fileUri.toString(), link.media.name, "", isLive = false)
                } else {
                    startUrl(link.media.url, link.media.name.ifBlank { link.media.url }, link.media.headers, isLive = false)
                }
            }
            else -> {
                // ACTION_VIEW from another app
                val data = intent.data ?: return
                if (data.scheme == "content" || data.scheme == "file") currentFileUri = data
                val name = if (data.scheme == "content" || data.scheme == "file") Files.info(contentResolver, data).name else data.toString()
                startUrl(data.toString(), name, "", isLive = false)
            }
        }
    }

    private fun channelsFor(group: String): List<Channel> {
        val all = repo.content.value?.channels ?: emptyList()
        val s = settings.current
        return when (group) {
            "Favourites" -> { val f = repo.favorites.value; ChannelOrganizer.sort(all.filter { it.id in f }, s.sortMode) }
            "All channels", "" -> ChannelOrganizer.sort(all, s.sortMode)
            else -> ChannelOrganizer.of(all).channelsIn(s.groupMode, group, s.sortMode)
        }
    }

    private fun setZapList(group: String) {
        val list = channelsFor(group)
        zapList = list.ifEmpty { channelsFor("All channels") }
        ui.groupName = if (list.isEmpty()) "All channels" else group.ifBlank { "All channels" }
    }

    private fun startChannel(channel: Channel) {
        zapIndex = zapList.indexOfFirst { it.id == channel.id }
        ui.channel = channel
        ui.title = channel.name
        ui.logo = channel.logo
        ui.number = channel.number
        ui.isLive = channel.type == ContentType.LIVE
        ui.isFavorite = channel.id in repo.favorites.value
        updateNowNext()
        repo.addRecent(channel.id)
        currentFileUri = null
        lifecycleScope.launch {
            val url = runCatching { repo.resolveUrl(channel) }.getOrElse { e -> ui.error = "Cannot resolve stream: ${e.message}"; return@launch }
            if (ui.channel?.id != channel.id) return@launch
            play(url, channel.headers)
            if (WatchSession.state.value.role == WatchSession.Role.HOST) {
                WatchSession.hostMediaChanged(MediaRef(MediaRef.Kind.URL, url = url, name = channel.name, headers = channel.headers))
            }
        }
    }

    private fun startUrl(url: String, title: String, headers: String, isLive: Boolean) {
        ui.channel = null
        ui.title = title
        ui.logo = ""
        ui.number = 0
        ui.isLive = isLive
        ui.nowProgramme = null; ui.nextProgramme = null
        ui.isFavorite = false
        if (zapList.isEmpty()) ui.groupName = ""
        play(url, headers)
    }

    private fun play(url: String, headers: String) {
        handler.removeCallbacks(retryRunnable)
        retryCount = 0
        currentUrl = url
        currentHeaders = headers
        ui.error = null
        ui.buffering = true
        okHttpFactory.setDefaultRequestProperties(headers.toHeaderMap())
        val uri = Uri.parse(url)
        val b = MediaItem.Builder().setUri(uri).setMediaId(url)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(ui.title).build())
        val lower = url.lowercase().substringBefore('?')
        when {
            lower.endsWith(".m3u8") || lower.contains(".m3u8?") || lower.contains("/hls/") -> b.setMimeType(MimeTypes.APPLICATION_M3U8)
            lower.endsWith(".mpd") -> b.setMimeType(MimeTypes.APPLICATION_MPD)
            lower.contains(".ism") -> b.setMimeType(MimeTypes.APPLICATION_SS)
            lower.endsWith(".ts") && ui.isLive -> b.setMimeType(MimeTypes.VIDEO_MP2T)
        }
        if (ui.isLive) b.setLiveConfiguration(MediaItem.LiveConfiguration.Builder().setMaxPlaybackSpeed(1.02f).build())
        player.setMediaItem(b.build())
        player.prepare()
        player.playWhenReady = true
        player.setPlaybackSpeed(1f)
        ui.speed = 1f
        ui.overlay = true
    }

    private fun zap(delta: Int) {
        if (zapList.isEmpty()) return
        val idx = if (zapIndex < 0) 0 else ((zapIndex + delta) % zapList.size + zapList.size) % zapList.size
        startChannel(zapList[idx])
    }

    private fun commitNumberInput() {
        val n = ui.numberInput.toIntOrNull()
        ui.numberInput = ""
        if (n == null) return
        val all = repo.content.value?.channels ?: return
        val target = all.firstOrNull { it.number == n } ?: run { showToast("No channel $n"); return }
        if (zapList.none { it.id == target.id }) setZapList("All channels")
        startChannel(target)
    }

    private fun updateNowNext() {
        val ch = ui.channel ?: return
        val (now, next) = repo.epg.value.nowNext(ch)
        ui.nowProgramme = now
        ui.nextProgramme = next
        if (now == null && ch.providerId.isNotBlank()) {
            lifecycleScope.launch {
                val list = repo.shortEpg(ch)
                if (ui.channel?.id != ch.id) return@launch
                val t = System.currentTimeMillis()
                ui.nowProgramme = list.firstOrNull { it.isLiveAt(t) }
                ui.nextProgramme = list.firstOrNull { it.start > t }
            }
        }
    }

    private fun showToast(msg: String) {
        ui.toast = msg
        handler.removeCallbacks(toastRunnable)
        handler.postDelayed(toastRunnable, 3500)
    }

    // ------------------------------------------------------------------ player

    private fun buildPlayer(): ExoPlayer {
        val s = settings.current
        val ua = s.userAgent.ifBlank { Http.DEFAULT_UA }
        okHttpFactory = OkHttpDataSource.Factory(Http.client).setUserAgent(ua)
        val dataSourceFactory = DefaultDataSource.Factory(this, okHttpFactory)
        val renderers = DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(
                when (s.decoderMode) {
                    DecoderMode.HARDWARE -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
                    DecoderMode.SOFTWARE_FIRST -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                    DecoderMode.AUTO -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
                },
            )
        val bm = s.bufferMode
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(bm.minMs, bm.maxMs, bm.playbackMs, bm.rebufferMs)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        trackSelector = DefaultTrackSelector(this)
        trackSelector.setParameters(
            trackSelector.buildUponParameters()
                .setTunnelingEnabled(s.tunneling)
                .setPreferredAudioLanguage(s.preferredAudioLang.ifBlank { null })
                .setPreferredTextLanguage(s.preferredSubtitleLang.ifBlank { null })
                .setSelectUndeterminedTextLanguage(false),
        )
        val p = ExoPlayer.Builder(this)
            .setRenderersFactory(renderers)
            .setLoadControl(loadControl)
            .setTrackSelector(trackSelector)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .build()
        p.addListener(playerListener)
        return p
    }

    private fun applyInitialQuality() {
        val cap = settings.current.qualityCap
        applyQualityCap(cap)
        ui.quality = cap.label
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            ui.buffering = playbackState == Player.STATE_BUFFERING
            if (playbackState == Player.STATE_READY) {
                retryCount = 0
                ui.error = null
                ui.isLive = player.isCurrentMediaItemLive || player.duration == C.TIME_UNSET
                updateVideoInfo()
            }
            if (playbackState == Player.STATE_ENDED && ui.isLive && currentUrl.isNotEmpty()) {
                // Live streams should not end; treat as a drop and reconnect.
                scheduleRetry()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) { ui.playing = isPlaying }

        override fun onTracksChanged(tracks: Tracks) { updateVideoInfo() }

        override fun onPlayerError(error: PlaybackException) {
            val msg = describe(error)
            if (ui.isLive && retryCount < 6 && !msg.startsWith("HTTP 4")) {
                scheduleRetry()
                showToast("Stream dropped - reconnecting ($retryCount/6)…")
            } else {
                ui.error = msg
            }
        }
    }

    private fun scheduleRetry() {
        retryCount++
        handler.removeCallbacks(retryRunnable)
        handler.postDelayed(retryRunnable, (1000L * retryCount).coerceAtMost(8000L))
    }

    private fun describe(e: PlaybackException): String {
        var cause: Throwable? = e
        while (cause != null) {
            if (cause is HttpDataSource.InvalidResponseCodeException) return "HTTP ${cause.responseCode}: stream refused (check subscription / connection limit)"
            if (cause is HttpDataSource.HttpDataSourceException) return "Network error: ${cause.cause?.message ?: cause.message}"
            cause = cause.cause
        }
        return when (e.errorCode) {
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackException.ERROR_CODE_DECODING_FAILED -> "This device cannot decode the stream (${e.errorCodeName})"
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED, PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> "Unsupported or broken stream format"
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Cannot reach the server"
            else -> e.errorCodeName.removePrefix("ERROR_CODE_").replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
        }
    }

    private fun updateVideoInfo() {
        val v = player.videoFormat
        val a = player.audioFormat
        ui.videoInfo = buildString {
            if (v != null) {
                append("${v.width}×${v.height}")
                if (v.frameRate > 0) append(" ${"%.0f".format(v.frameRate)}fps")
                append("  ").append((v.codecs ?: v.sampleMimeType ?: "").substringAfter('/'))
            }
            if (a != null) {
                if (isNotEmpty()) append("   ")
                append((a.codecs ?: a.sampleMimeType ?: "").substringAfter('/'))
                if (a.channelCount > 0) append(" ${a.channelCount}ch")
                if (a.sampleRate > 0) append(" ${a.sampleRate / 1000}kHz")
            }
        }
    }

    // ------------------------------------------------------------------ actions exposed to Compose

    private val actions = object : PlayerActions {
        override fun togglePlay() { if (player.isPlaying) player.pause() else { if (player.playbackState == Player.STATE_IDLE) player.prepare(); player.play() } }
        override fun seekBy(ms: Long) { if (!ui.isLive) player.seekTo((player.currentPosition + ms).coerceIn(0, player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE)) }
        override fun seekTo(ms: Long) { player.seekTo(ms) }
        override fun zap(delta: Int) = this@PlayerActivity.zap(delta)
        override fun selectChannel(channel: Channel, group: String) { setZapList(group); startChannel(channel) }
        override fun channelsInGroup(group: String): List<Channel> = channelsFor(group)
        override fun groups(): List<String> {
            val all = repo.content.value?.channels ?: emptyList()
            return listOf("All channels", "Favourites") + ChannelOrganizer.of(all).groups(settings.current.groupMode).map { it.key }
        }
        override fun groupModeLabel(): String = settings.current.groupMode.label
        override fun cycleGroupMode() {
            settings.update { it.copy(groupMode = com.mypro.iptvbox.core.util.GroupMode.entries[(it.groupMode.ordinal + 1) % com.mypro.iptvbox.core.util.GroupMode.entries.size]) }
        }
        override fun videoTracks(): List<TrackOption> = tracksOf(C.TRACK_TYPE_VIDEO)
        override fun qualityLabel(): String = ui.quality
        override fun setQualityCap(cap: QualityCap) {
            applyQualityCap(cap)
            ui.quality = cap.label
            if (cap == QualityCap.AUTO || cap == QualityCap.LOWEST) settings.update { it.copy(qualityCap = cap) }
        }
        override fun selectVideoTrack(option: TrackOption) {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setForceLowestBitrate(false).setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE)
                .setOverrideForType(TrackSelectionOverride(option.group.mediaTrackGroup, option.index)).build()
            ui.quality = option.label
        }
        override fun programmes(channel: Channel): List<Programme> {
            val t = System.currentTimeMillis()
            return repo.epg.value.between(channel, t - 12 * 3600_000L, t + 24 * 3600_000L)
        }
        override suspend fun shortEpg(channel: Channel): List<Programme> = repo.shortEpg(channel)
        override fun playCatchup(channel: Channel, programme: Programme) {
            val url = repo.catchupUrl(channel, programme) ?: run { showToast("Catch-up not available for this channel"); return }
            ui.epgPanel = false
            startUrl(url, "${channel.name} • ${programme.title}", channel.headers, isLive = false)
        }
        override fun toggleFavorite() {
            val ch = ui.channel ?: return
            repo.toggleFavorite(ch.id)
            ui.isFavorite = ch.id in repo.favorites.value
            showToast(if (ui.isFavorite) "Added to favourites" else "Removed from favourites")
        }
        override fun setResizeMode(mode: Int) { ui.resizeMode = mode }
        override fun setSpeed(speed: Float) { player.setPlaybackSpeed(speed); ui.speed = speed }
        override fun audioTracks(): List<TrackOption> = tracksOf(C.TRACK_TYPE_AUDIO)
        override fun subtitleTracks(): List<TrackOption> = tracksOf(C.TRACK_TYPE_TEXT)
        override fun selectTrack(option: TrackOption?) {
            val params = player.trackSelectionParameters.buildUpon()
            if (option == null) {
                params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            } else {
                params.setTrackTypeDisabled(option.type, false)
                params.setOverrideForType(TrackSelectionOverride(option.group.mediaTrackGroup, option.index))
            }
            player.trackSelectionParameters = params.build()
        }
        override fun retry() { ui.error = null; retryCount = 0; player.prepare(); player.play() }
        override fun exit() { finish() }
        override fun showHostPicker() { ui.menu = false; ui.hostPicker = true }
        override fun startHosting(mode: WatchLink.Mode) {
            ui.hostPicker = false
            val media = currentMediaRef() ?: run { showToast("Nothing is playing"); return }
            if (mode == WatchLink.Mode.LAN && Net.localIpv4() == null) { showToast("No Wi-Fi/LAN address found"); return }
            val link = WatchSession.host(mode, media, settings.current.relayBroker, Net.localIpv4(), settings.current.displayName)
            ui.watchDialog = link
        }
        override fun showInvite() { ui.menu = false; ui.watchDialog = WatchSession.state.value.link }
        override fun leaveWatch() { WatchSession.leave(); ui.menu = false; showToast("Left watch party") }
        override fun shareText(text: String) {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).putExtra(Intent.EXTRA_SUBJECT, "Watch together on IPTV Box")
            runCatching { startActivity(Intent.createChooser(send, "Share invitation")) }.onFailure { showToast("No app available to share") }
        }
        override fun copyText(text: String) {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("IPTV Box invitation", text))
            showToast("Copied to clipboard")
        }
        override fun openExternal() {
            val i = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(currentUrl), if (currentUrl.contains(".m3u8")) "application/x-mpegURL" else "video/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            runCatching { startActivity(Intent.createChooser(i, "Open with")) }.onFailure { showToast("No external player found") }
        }
        override fun onNumberKey(digit: Char) = appendNumber(digit)
    }

    private fun applyQualityCap(cap: QualityCap) {
        val b = player.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO)
        when {
            cap == QualityCap.LOWEST -> b.setForceLowestBitrate(true).setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE)
            cap.maxHeight > 0 -> b.setForceLowestBitrate(false).setMaxVideoSize(Int.MAX_VALUE, cap.maxHeight)
            else -> b.setForceLowestBitrate(false).setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE)
        }
        player.trackSelectionParameters = b.build()
    }

    private fun tracksOf(type: Int): List<TrackOption> {
        val out = ArrayList<TrackOption>()
        for (group in player.currentTracks.groups) {
            if (group.type != type) continue
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i)) continue
                val f = group.getTrackFormat(i)
                val label = if (type == C.TRACK_TYPE_VIDEO) {
                    listOfNotNull(
                        if (f.height > 0) "${f.height}p" else null,
                        if (f.width > 0 && f.height > 0) "${f.width}×${f.height}" else null,
                        if (f.bitrate > 0) String.format(java.util.Locale.US, "%.1f Mbps", f.bitrate / 1_000_000f) else null,
                        if (f.frameRate > 0) "${f.frameRate.toInt()} fps" else null,
                    ).joinToString(" · ").ifBlank { "Variant ${out.size + 1}" }
                } else {
                    listOfNotNull(f.label, f.language?.let { java.util.Locale(it).displayLanguage }, f.codecs?.substringBefore('.')?.uppercase(), if (f.channelCount > 0) "${f.channelCount}ch" else null)
                        .distinct().joinToString(" · ").ifBlank { "Track ${out.size + 1}" }
                }
                out += TrackOption(type, group, i, label, group.isTrackSelected(i))
            }
        }
        return out
    }

    private fun currentMediaRef(): MediaRef? {
        if (currentUrl.isBlank()) return null
        val fileUri = currentFileUri
        return if (fileUri != null && (fileUri.scheme == "content" || fileUri.scheme == "file")) {
            val info = Files.info(contentResolver, fileUri)
            MediaRef(MediaRef.Kind.FILE, name = info.name, size = info.size)
        } else {
            MediaRef(MediaRef.Kind.URL, url = currentUrl, name = ui.title, headers = currentHeaders)
        }
    }

    private fun appendNumber(digit: Char) {
        if (ui.numberInput.length >= 4) return
        ui.numberInput += digit
        handler.removeCallbacks(numberRunnable)
        handler.postDelayed(numberRunnable, 1500)
    }

    // ------------------------------------------------------------------ remote control keys

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_CHANNEL_UP -> { zap(1); return true }
                KeyEvent.KEYCODE_CHANNEL_DOWN -> { zap(-1); return true }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { actions.togglePlay(); ui.overlay = true; return true }
                KeyEvent.KEYCODE_MEDIA_PLAY -> { player.play(); return true }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> { player.pause(); ui.overlay = true; return true }
                KeyEvent.KEYCODE_MEDIA_STOP -> { finish(); return true }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { actions.seekBy(30_000); ui.overlay = true; return true }
                KeyEvent.KEYCODE_MEDIA_REWIND -> { actions.seekBy(-15_000); ui.overlay = true; return true }
                KeyEvent.KEYCODE_MEDIA_NEXT -> { zap(1); return true }
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { zap(-1); return true }
                KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> { if (!ui.anyPanel) { ui.menu = true; return true } }
                KeyEvent.KEYCODE_GUIDE, KeyEvent.KEYCODE_PROG_RED -> { if (!ui.anyPanel && ui.channel != null) { ui.epgPanel = true; return true } }
                KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_PROG_GREEN -> { ui.overlay = !ui.overlay; return true }
                KeyEvent.KEYCODE_PROG_YELLOW -> { if (!ui.anyPanel) { ui.menuInitialSub = "Video quality"; ui.menu = true; return true } }
            }
            if (event.keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 && !ui.anyPanel && zapList.isNotEmpty()) {
                appendNumber('0' + (event.keyCode - KeyEvent.KEYCODE_0)); return true
            }
            if (event.keyCode in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 && !ui.anyPanel && zapList.isNotEmpty()) {
                appendNumber('0' + (event.keyCode - KeyEvent.KEYCODE_NUMPAD_0)); return true
            }
            if (!ui.anyPanel) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> { if (ui.channel != null) zap(1) else ui.overlay = true; return true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> { if (ui.channel != null) zap(-1) else ui.overlay = true; return true }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        if (ui.channel != null && zapList.isNotEmpty()) ui.channelList = true else { actions.togglePlay(); ui.overlay = true }
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> { if (ui.isLive) { if (ui.channel != null) ui.epgPanel = true } else { actions.seekBy(-10_000); ui.overlay = true }; return true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { if (ui.isLive) { if (ui.channel != null) ui.epgPanel = true } else { actions.seekBy(10_000); ui.overlay = true }; return true }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ------------------------------------------------------------------ lifecycle / PiP

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        enterPipIfPossible()
    }

    private fun enterPipIfPossible() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (!settings.current.pipEnabled || !player.isPlaying || ui.error != null) return
        if (!packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
        val v = player.videoFormat
        val ratio = if (v != null && v.width > 0 && v.height > 0) Rational(v.width.coerceIn(1, 10000), v.height.coerceIn(1, 10000)) else Rational(16, 9)
        val safe = if (ratio.toFloat() in 0.42f..2.39f) ratio else Rational(16, 9)
        runCatching { enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(safe).build()) }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPip = isInPictureInPictureMode
        ui.overlay = !isInPictureInPictureMode && ui.overlay
        if (!isInPictureInPictureMode) hideSystemBars()
    }

    override fun onStop() {
        super.onStop()
        if (!isInPip && !isChangingConfigurations) player.pause()
    }

    override fun onStart() {
        super.onStart()
        if (!player.isPlaying && player.playbackState != Player.STATE_IDLE && ui.error == null && !isInPip) {
            if (ui.isLive) { player.seekToDefaultPosition(); player.prepare() }
            player.play()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        WatchSession.onEvent = {}
        WatchSession.onLoadRequested = { _, _ -> }
        WatchSession.detachPlayer(player)
        // Leaving the player ends the party for this device (host or viewer).
        WatchSession.leave()
        player.release()
        super.onDestroy()
    }

    private fun hideSystemBars() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }
}

/** One selectable audio/subtitle track. */
data class TrackOption(val type: Int, val group: Tracks.Group, val index: Int, val label: String, val selected: Boolean)

interface PlayerActions {
    fun togglePlay()
    fun seekBy(ms: Long)
    fun seekTo(ms: Long)
    fun zap(delta: Int)
    fun selectChannel(channel: Channel, group: String)
    fun channelsInGroup(group: String): List<Channel>
    fun groups(): List<String>
    fun programmes(channel: Channel): List<Programme>
    suspend fun shortEpg(channel: Channel): List<Programme>
    fun playCatchup(channel: Channel, programme: Programme)
    fun toggleFavorite()
    fun setResizeMode(mode: Int)
    fun setSpeed(speed: Float)
    fun audioTracks(): List<TrackOption>
    fun subtitleTracks(): List<TrackOption>
    fun selectTrack(option: TrackOption?)
    fun retry()
    fun exit()
    fun showHostPicker()
    fun startHosting(mode: WatchLink.Mode)
    fun showInvite()
    fun leaveWatch()
    fun shareText(text: String)
    fun copyText(text: String)
    fun openExternal()
    fun onNumberKey(digit: Char)
    fun groupModeLabel(): String
    fun cycleGroupMode()
    fun videoTracks(): List<TrackOption>
    fun qualityLabel(): String
    fun setQualityCap(cap: QualityCap)
    fun selectVideoTrack(option: TrackOption)
}
