package com.mypro.iptvbox.core.xtream

import com.mypro.iptvbox.core.model.Channel
import com.mypro.iptvbox.core.model.ContentType
import com.mypro.iptvbox.core.model.Episode
import com.mypro.iptvbox.core.model.Playlist
import com.mypro.iptvbox.core.model.PlaylistContent
import com.mypro.iptvbox.core.model.Programme
import com.mypro.iptvbox.core.model.SeriesItem
import com.mypro.iptvbox.core.model.VodItem
import com.mypro.iptvbox.core.net.Http
import com.mypro.iptvbox.core.util.Ids
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Locale
import java.util.TimeZone

/**
 * Client for the Xtream Codes compatible "player_api.php" used by most IPTV panels.
 * Field types in these APIs are inconsistent (numbers as strings, missing keys), so
 * everything is read defensively through [JsonElement].
 */
class XtreamApi(val playlist: Playlist) {

    val base: String = normalizeBase(playlist.url)
    private val user = playlist.username
    private val pass = playlist.password
    private val ua get() = playlist.userAgent

    data class Account(
        val status: String,
        val expiresAt: Long,
        val activeConnections: Int,
        val maxConnections: Int,
        val allowedFormats: List<String>,
        val serverTimeOffsetMs: Long,
        val timezone: String,
    )

    private fun api(action: String? = null, vararg params: Pair<String, String>): JsonElement {
        val sb = StringBuilder(base).append("/player_api.php?username=").append(enc(user)).append("&password=").append(enc(pass))
        if (action != null) sb.append("&action=").append(action)
        for ((k, v) in params) sb.append('&').append(k).append('=').append(enc(v))
        val body = Http.getString(sb.toString(), ua)
        return json.parseToJsonElement(body)
    }

    fun authenticate(): Account {
        val root = api().jsonObject
        val ui = root["user_info"]?.asObjectOrNull() ?: throw IllegalStateException("Login failed: no user_info in reply")
        val auth = ui.int("auth")
        val status = ui.str("status")
        if (auth == 0 || status.equals("Disabled", true) || status.equals("Banned", true) || status.equals("Expired", true)) {
            throw IllegalStateException("Login refused: ${status.ifBlank { "invalid username or password" }}")
        }
        val si = root["server_info"]?.asObjectOrNull()
        val serverNow = si?.long("timestamp_now") ?: 0L
        val offset = if (serverNow > 0) serverNow * 1000 - System.currentTimeMillis() else 0L
        return Account(
            status = status,
            expiresAt = ui.long("exp_date") * 1000,
            activeConnections = ui.int("active_cons"),
            maxConnections = ui.int("max_connections"),
            allowedFormats = ui["allowed_output_formats"]?.asArrayOrNull()?.mapNotNull { it.asStringOrNull() } ?: emptyList(),
            serverTimeOffsetMs = offset,
            timezone = si?.str("timezone") ?: "",
        )
    }

    private fun categories(action: String): Map<String, String> {
        val arr = api(action).asArrayOrNull() ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (e in arr) {
            val o = e.asObjectOrNull() ?: continue
            out[o.str("category_id")] = o.str("category_name")
        }
        return out
    }

    fun liveChannels(preferredExt: String = "ts"): List<Channel> {
        val cats = categories("get_live_categories")
        val arr = api("get_live_streams").asArrayOrNull() ?: return emptyList()
        val out = ArrayList<Channel>(arr.size)
        var n = 0
        for (e in arr) {
            val o = e.asObjectOrNull() ?: continue
            val id = o.str("stream_id")
            if (id.isEmpty()) continue
            n++
            val name = o.str("name")
            out += Channel(
                id = Ids.stable(playlist.id, "live", id),
                playlistId = playlist.id,
                name = name,
                url = liveUrl(id, preferredExt),
                group = cats[o.str("category_id")] ?: "Uncategorised",
                logo = o.str("stream_icon"),
                tvgId = o.str("epg_channel_id"),
                tvgName = name,
                number = o.int("num").takeIf { it > 0 } ?: n,
                type = ContentType.LIVE,
                catchupDays = if (o.int("tv_archive") == 1) o.int("tv_archive_duration").coerceAtLeast(1) else 0,
                providerId = id,
                extension = preferredExt,
            )
        }
        return out
    }

    fun movies(): List<VodItem> {
        val cats = categories("get_vod_categories")
        val arr = api("get_vod_streams").asArrayOrNull() ?: return emptyList()
        val out = ArrayList<VodItem>(arr.size)
        for (e in arr) {
            val o = e.asObjectOrNull() ?: continue
            val id = o.str("stream_id")
            if (id.isEmpty()) continue
            val ext = o.str("container_extension").ifBlank { "mp4" }
            out += VodItem(
                id = Ids.stable(playlist.id, "movie", id),
                playlistId = playlist.id,
                name = o.str("name"),
                url = "$base/movie/${enc(user)}/${enc(pass)}/$id.$ext",
                group = cats[o.str("category_id")] ?: "Uncategorised",
                poster = o.str("stream_icon"),
                rating = o.str("rating"),
                year = o.str("year").ifBlank { o.str("releaseDate").take(4) },
                plot = o.str("plot"),
                extension = ext,
                providerId = id,
            )
        }
        return out
    }

    fun series(): List<SeriesItem> {
        val cats = categories("get_series_categories")
        val arr = api("get_series").asArrayOrNull() ?: return emptyList()
        val out = ArrayList<SeriesItem>(arr.size)
        for (e in arr) {
            val o = e.asObjectOrNull() ?: continue
            val id = o.str("series_id")
            if (id.isEmpty()) continue
            out += SeriesItem(
                id = Ids.stable(playlist.id, "series", id),
                playlistId = playlist.id,
                name = o.str("name"),
                group = cats[o.str("category_id")] ?: "Uncategorised",
                poster = o.str("cover"),
                plot = o.str("plot"),
                rating = o.str("rating"),
                year = o.str("releaseDate").take(4),
                providerId = id,
            )
        }
        return out
    }

    /** Episodes for a series, sorted by season then episode. */
    fun episodes(series: SeriesItem): List<Episode> {
        val root = api("get_series_info", "series_id" to series.providerId).asObjectOrNull() ?: return emptyList()
        val episodesEl = root["episodes"] ?: return emptyList()
        val lists: List<JsonElement> = when (episodesEl) {
            is JsonObject -> episodesEl.values.toList()
            is JsonArray -> episodesEl.toList()
            else -> emptyList()
        }
        val out = ArrayList<Episode>()
        for (seasonList in lists) {
            val eps = seasonList.asArrayOrNull() ?: continue
            for (e in eps) {
                val o = e.asObjectOrNull() ?: continue
                val id = o.str("id")
                if (id.isEmpty()) continue
                val ext = o.str("container_extension").ifBlank { "mp4" }
                val info = o["info"]?.asObjectOrNull()
                out += Episode(
                    id = Ids.stable(playlist.id, "episode", id),
                    seriesId = series.id,
                    season = o.int("season"),
                    episode = o.int("episode_num"),
                    title = o.str("title"),
                    url = "$base/series/${enc(user)}/${enc(pass)}/$id.$ext",
                    poster = info?.str("movie_image") ?: "",
                    plot = info?.str("plot") ?: "",
                    duration = info?.str("duration") ?: "",
                )
            }
        }
        out.sortWith(compareBy({ it.season }, { it.episode }))
        return out
    }

    /** Short EPG straight from the panel (works even when there is no XMLTV). */
    fun shortEpg(channel: Channel, limit: Int = 10): List<Programme> {
        val root = api("get_short_epg", "stream_id" to channel.providerId, "limit" to limit.toString()).asObjectOrNull() ?: return emptyList()
        val arr = root["epg_listings"]?.asArrayOrNull() ?: return emptyList()
        return arr.mapNotNull { e ->
            val o = e.asObjectOrNull() ?: return@mapNotNull null
            val start = o.long("start_timestamp") * 1000
            val stop = o.long("stop_timestamp") * 1000
            if (start == 0L) return@mapNotNull null
            Programme(
                channelId = channel.tvgId.ifBlank { channel.providerId },
                start = start,
                stop = if (stop > start) stop else start + 30 * 60_000,
                title = b64(o.str("title")),
                description = b64(o.str("description")),
            )
        }
    }

    fun liveUrl(streamId: String, ext: String = "ts"): String =
        if (ext == "m3u8") "$base/live/${enc(user)}/${enc(pass)}/$streamId.m3u8"
        else "$base/live/${enc(user)}/${enc(pass)}/$streamId.ts"

    /**
     * Catch-up / timeshift URL for a programme that already aired. Panels read the start time
     * in their own timezone (server_info.timezone), not UTC.
     */
    fun catchupUrl(channel: Channel, startUtcMs: Long, durationMs: Long, serverTimezone: String = ""): String =
        buildCatchupUrl(base, user, pass, channel.providerId, startUtcMs, durationMs, serverTimezone)

    val epgUrl: String get() = "$base/xmltv.php?username=${enc(user)}&password=${enc(pass)}"

    /** Loads everything in one go. */
    fun loadAll(includeVod: Boolean = true, liveExt: String = "ts"): PlaylistContent {
        val account = authenticate()
        // Respect accounts that only allow one output format.
        val formats = account.allowedFormats.map { it.lowercase() }
        val ext = when {
            formats.isEmpty() || liveExt in formats -> liveExt
            "m3u8" in formats -> "m3u8"
            "ts" in formats -> "ts"
            else -> liveExt
        }
        val live = liveChannels(ext)
        val movies = if (includeVod) runCatching { movies() }.getOrDefault(emptyList()) else emptyList()
        val series = if (includeVod) runCatching { series() }.getOrDefault(emptyList()) else emptyList()
        return PlaylistContent(
            playlistId = playlist.id,
            channels = live,
            movies = movies,
            series = series,
            epgUrl = playlist.epgUrl.ifBlank { epgUrl },
            loadedAt = System.currentTimeMillis(),
            serverTimeOffsetMs = account.serverTimeOffsetMs,
            serverTimezone = account.timezone,
        )
    }

    companion object {
        val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

        fun normalizeBase(raw: String): String {
            var u = raw.trim()
            if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://$u"
            // Users often paste a full get.php / player_api.php URL - keep only scheme://host:port
            val m = Regex("""^(https?://[^/?#]+)""").find(u)
            return (m?.groupValues?.get(1) ?: u).trimEnd('/')
        }

        /** Parses an M3U "get.php?username=&password=" style URL into Xtream credentials. */
        fun fromGetPhpUrl(url: String): Triple<String, String, String>? {
            if (!url.contains("get.php") && !url.contains("player_api.php")) return null
            val q = url.substringAfter('?', "")
            val params = q.split('&').mapNotNull { kv ->
                val i = kv.indexOf('=')
                if (i <= 0) null else kv.substring(0, i) to java.net.URLDecoder.decode(kv.substring(i + 1), "UTF-8")
            }.toMap()
            val u = params["username"] ?: return null
            val p = params["password"] ?: return null
            return Triple(normalizeBase(url), u, p)
        }

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

        fun buildCatchupUrl(base: String, user: String, pass: String, streamId: String, startUtcMs: Long, durationMs: Long, serverTimezone: String): String {
            val tz = if (serverTimezone.isBlank()) TimeZone.getTimeZone("UTC") else TimeZone.getTimeZone(serverTimezone)
            val fmt = SimpleDateFormat("yyyy-MM-dd:HH-mm", Locale.US).apply { timeZone = tz }
            val durationMin = ((durationMs + 59_999) / 60_000).coerceAtLeast(1)
            val start = fmt.format(java.util.Date(startUtcMs))
            return "${normalizeBase(base)}/timeshift/${enc(user)}/${enc(pass)}/$durationMin/$start/$streamId.ts"
        }

        private fun b64(s: String): String = try {
            if (s.isEmpty()) s else String(Base64.getDecoder().decode(s.trim()), Charsets.UTF_8)
        } catch (e: Exception) { s }

        internal fun JsonElement.asObjectOrNull(): JsonObject? = this as? JsonObject
        internal fun JsonElement.asArrayOrNull(): JsonArray? = this as? JsonArray
        internal fun JsonElement.asStringOrNull(): String? = (this as? JsonPrimitive)?.contentOrNull
        internal fun JsonObject.str(key: String): String {
            val e = this[key] ?: return ""
            return when (e) {
                is JsonNull -> ""
                is JsonPrimitive -> e.contentOrNull ?: ""
                else -> e.toString()
            }
        }
        internal fun JsonObject.int(key: String): Int = str(key).trim().toDoubleOrNull()?.toInt() ?: 0
        internal fun JsonObject.long(key: String): Long = str(key).trim().toDoubleOrNull()?.toLong() ?: 0L
    }
}
