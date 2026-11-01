package com.mypro.iptvbox.core.model

import kotlinx.serialization.Serializable

/** How a playlist source is reached. */
@Serializable
enum class SourceType { M3U_URL, M3U_FILE, XTREAM, STALKER }

@Serializable
enum class ContentType { LIVE, MOVIE, SERIES }

/** A configured IPTV source (one "account" or one M3U list). */
@Serializable
data class Playlist(
    val id: String,
    val name: String,
    val type: SourceType,
    /** M3U URL, local file path/URI, Xtream server base URL or Stalker portal URL. */
    val url: String = "",
    val username: String = "",
    val password: String = "",
    /** Stalker MAC address, e.g. 00:1A:79:xx:xx:xx */
    val macAddress: String = "",
    /** XMLTV URL. Empty = auto (M3U url-tvg header or Xtream xmltv.php). */
    val epgUrl: String = "",
    val userAgent: String = "",
    val lastRefreshed: Long = 0L,
)

@Serializable
data class Channel(
    val id: String,
    val playlistId: String,
    val name: String,
    val url: String,
    val group: String = "",
    val logo: String = "",
    /** XMLTV channel id used to find programmes. */
    val tvgId: String = "",
    val tvgName: String = "",
    val number: Int = 0,
    val type: ContentType = ContentType.LIVE,
    /** Days of catch-up available (0 = none). */
    val catchupDays: Int = 0,
    val catchupSource: String = "",
    /** Xtream stream id, Stalker channel id, etc. */
    val providerId: String = "",
    /** Extra HTTP headers encoded as key=value lines (from #EXTVLCOPT). */
    val headers: String = "",
    /** Hints for the player: Xtream container extension etc. */
    val extension: String = "",
    /** ISO country code or name from tvg-country (optional). */
    val country: String = "",
    /** Language from tvg-language (optional). */
    val language: String = "",
)

@Serializable
data class Programme(
    val channelId: String,
    val start: Long,
    val stop: Long,
    val title: String,
    val description: String = "",
    val category: String = "",
) {
    fun isLiveAt(t: Long) = t in start until stop
    val durationMs get() = stop - start
}

@Serializable
data class VodItem(
    val id: String,
    val playlistId: String,
    val name: String,
    val url: String,
    val group: String = "",
    val poster: String = "",
    val plot: String = "",
    val rating: String = "",
    val year: String = "",
    val duration: String = "",
    val extension: String = "",
    val providerId: String = "",
)

@Serializable
data class SeriesItem(
    val id: String,
    val playlistId: String,
    val name: String,
    val group: String = "",
    val poster: String = "",
    val plot: String = "",
    val rating: String = "",
    val year: String = "",
    val providerId: String = "",
)

@Serializable
data class Episode(
    val id: String,
    val seriesId: String,
    val season: Int,
    val episode: Int,
    val title: String,
    val url: String,
    val poster: String = "",
    val plot: String = "",
    val duration: String = "",
)

@Serializable
data class ChannelGroup(val name: String, val count: Int)

/** Everything we loaded from one playlist. */
@Serializable
data class PlaylistContent(
    val playlistId: String,
    val channels: List<Channel> = emptyList(),
    val movies: List<VodItem> = emptyList(),
    val series: List<SeriesItem> = emptyList(),
    val epgUrl: String = "",
    val loadedAt: Long = 0L,
    val serverTimeOffsetMs: Long = 0L,
)
