package com.mypro.iptvbox.data

import android.content.Context
import android.content.SharedPreferences
import com.mypro.iptvbox.core.sync.WatchLink
import com.mypro.iptvbox.core.util.GroupMode
import com.mypro.iptvbox.core.util.SortMode
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

/** Colour themes. Values are ARGB. */
enum class ThemeId(val label: String, val primary: Long, val secondary: Long, val background: Long, val surface: Long, val surfaceVariant: Long, val onPrimary: Long = 0xFF00201C) {
    OCEAN("Ocean", 0xFF2EC4B6, 0xFFFFB703, 0xFF0B1220, 0xFF121A2B, 0xFF1B2538),
    SUNSET("Sunset", 0xFFFF7A45, 0xFFFFD166, 0xFF150C12, 0xFF22131A, 0xFF301B24, 0xFF2B0A00),
    ROYAL("Royal", 0xFFB388FF, 0xFF64FFDA, 0xFF0E0A1E, 0xFF191230, 0xFF261B44, 0xFF1D0A3A),
    EMERALD("Emerald", 0xFF4ADE80, 0xFFFACC15, 0xFF07130D, 0xFF0F1F16, 0xFF183222, 0xFF032611),
    CRIMSON("Crimson", 0xFFFF5C7A, 0xFFFFC857, 0xFF140A0E, 0xFF221017, 0xFF321824, 0xFF3A0010),
    GOLD("Gold", 0xFFF5B942, 0xFF7DD3FC, 0xFF110E08, 0xFF1C1710, 0xFF2A2318, 0xFF2E1E00),
    MIDNIGHT("Midnight (OLED black)", 0xFF60A5FA, 0xFFF472B6, 0xFF000000, 0xFF0B0B0D, 0xFF17171B, 0xFF001A3A),
}

/** Maximum video quality for adaptive (HLS/DASH) streams. */
enum class QualityCap(val label: String, val maxHeight: Int) {
    AUTO("Auto (best available)", 0),
    Q1080("Up to 1080p", 1080),
    Q720("Up to 720p", 720),
    Q480("Up to 480p", 480),
    Q360("Up to 360p", 360),
    LOWEST("Lowest (data saver)", -1),
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
    val theme: ThemeId = ThemeId.OCEAN,
    val qualityCap: QualityCap = QualityCap.AUTO,
    val groupMode: GroupMode = GroupMode.PROVIDER,
    val sortMode: SortMode = SortMode.NUMBER,
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
        theme = enumOr(prefs.getString("theme", null), ThemeId.OCEAN),
        qualityCap = enumOr(prefs.getString("qualityCap", null), QualityCap.AUTO),
        groupMode = enumOr(prefs.getString("groupMode", null), GroupMode.PROVIDER),
        sortMode = enumOr(prefs.getString("sortMode", null), SortMode.NUMBER),
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
            .putString("theme", s.theme.name)
            .putString("qualityCap", s.qualityCap.name)
            .putString("groupMode", s.groupMode.name)
            .putString("sortMode", s.sortMode.name)
            .apply()
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, def: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: def
}
