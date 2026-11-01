package com.mypro.iptvbox.data

import android.content.Context
import android.content.SharedPreferences
import com.mypro.iptvbox.core.sync.WatchLink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class BufferMode(val label: String, val minMs: Int, val maxMs: Int, val playbackMs: Int, val rebufferMs: Int) {
    LOW("Low latency", 2_000, 15_000, 500, 1_500),
    NORMAL("Normal", 15_000, 50_000, 2_000, 4_000),
    HIGH("Large (unstable connections)", 30_000, 120_000, 4_000, 8_000),
}

enum class LiveFormat(val label: String, val ext: String) { TS("MPEG-TS (.ts)", "ts"), HLS("HLS (.m3u8)", "m3u8") }

enum class DecoderMode(val label: String) {
    AUTO("Hardware, fall back to software"),
    HARDWARE("Hardware only"),
    SOFTWARE_FIRST("Prefer software decoders"),
}

data class AppSettings(
    val activePlaylistId: String = "",
    val bufferMode: BufferMode = BufferMode.NORMAL,
    val liveFormat: LiveFormat = LiveFormat.TS,
    val decoderMode: DecoderMode = DecoderMode.AUTO,
    val userAgent: String = "",
    val displayName: String = android.os.Build.MODEL ?: "TV",
    val relayBroker: String = WatchLink.DEFAULT_BROKER,
    val autoPlayLast: Boolean = false,
    val lastChannelId: String = "",
    val epgRefreshHours: Int = 12,
    val pipEnabled: Boolean = true,
    val tunneling: Boolean = false,
    val showChannelNumbers: Boolean = true,
    val overlayTimeoutSec: Int = 6,
    val preferredAudioLang: String = "",
    val preferredSubtitleLang: String = "",
    val externalPlayer: Boolean = false,
)

class Settings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("iptvbox", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppSettings> = _state.asStateFlow()
    val current: AppSettings get() = _state.value

    private fun load(): AppSettings = AppSettings(
        activePlaylistId = prefs.getString("activePlaylistId", "") ?: "",
        bufferMode = enumOr(prefs.getString("bufferMode", null), BufferMode.NORMAL),
        liveFormat = enumOr(prefs.getString("liveFormat", null), LiveFormat.TS),
        decoderMode = enumOr(prefs.getString("decoderMode", null), DecoderMode.AUTO),
        userAgent = prefs.getString("userAgent", "") ?: "",
        displayName = prefs.getString("displayName", null) ?: (android.os.Build.MODEL ?: "TV"),
        relayBroker = prefs.getString("relayBroker", WatchLink.DEFAULT_BROKER) ?: WatchLink.DEFAULT_BROKER,
        autoPlayLast = prefs.getBoolean("autoPlayLast", false),
        lastChannelId = prefs.getString("lastChannelId", "") ?: "",
        epgRefreshHours = prefs.getInt("epgRefreshHours", 12),
        pipEnabled = prefs.getBoolean("pipEnabled", true),
        tunneling = prefs.getBoolean("tunneling", false),
        showChannelNumbers = prefs.getBoolean("showChannelNumbers", true),
        overlayTimeoutSec = prefs.getInt("overlayTimeoutSec", 6),
        preferredAudioLang = prefs.getString("preferredAudioLang", "") ?: "",
        preferredSubtitleLang = prefs.getString("preferredSubtitleLang", "") ?: "",
        externalPlayer = prefs.getBoolean("externalPlayer", false),
    )

    fun update(block: (AppSettings) -> AppSettings) {
        val s = block(_state.value)
        _state.value = s
        prefs.edit()
            .putString("activePlaylistId", s.activePlaylistId)
            .putString("bufferMode", s.bufferMode.name)
            .putString("liveFormat", s.liveFormat.name)
            .putString("decoderMode", s.decoderMode.name)
            .putString("userAgent", s.userAgent)
            .putString("displayName", s.displayName)
            .putString("relayBroker", s.relayBroker)
            .putBoolean("autoPlayLast", s.autoPlayLast)
            .putString("lastChannelId", s.lastChannelId)
            .putInt("epgRefreshHours", s.epgRefreshHours)
            .putBoolean("pipEnabled", s.pipEnabled)
            .putBoolean("tunneling", s.tunneling)
            .putBoolean("showChannelNumbers", s.showChannelNumbers)
            .putInt("overlayTimeoutSec", s.overlayTimeoutSec)
            .putString("preferredAudioLang", s.preferredAudioLang)
            .putString("preferredSubtitleLang", s.preferredSubtitleLang)
            .putBoolean("externalPlayer", s.externalPlayer)
            .apply()
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, def: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: def
}
