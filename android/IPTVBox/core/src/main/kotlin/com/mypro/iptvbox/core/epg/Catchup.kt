package com.mypro.iptvbox.core.epg

import com.mypro.iptvbox.core.model.Channel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Builds catch-up URLs for M3U channels, following the conventions used by Kodi
 * IPTV Simple / TiviMate: catchup="default|append|shift|flussonic" with catchup-source
 * templates such as {utc}, {utcend}, {lutc}, {duration}, {Y}{m}{d}{H}{M}{S},
 * ${start}, ${end}, ${timestamp} and ${start:yyyy-MM-dd-HH-mm-ss}.
 */
object Catchup {

    fun buildUrl(channel: Channel, startMs: Long, endMs: Long, nowMs: Long = System.currentTimeMillis()): String? {
        val type = channel.catchupType.lowercase()
        val src = channel.catchupSource
        return when {
            type == "shift" || type == "timeshift" ->
                appendQuery(channel.url, "utc={utc}&lutc={lutc}").let { fill(it, startMs, endMs, nowMs) }
            type == "flussonic" || type == "flussonic-hls" || type == "flussonic-ts" || type == "fs" ->
                flussonic(channel.url, startMs, endMs)
            type == "append" ->
                if (src.isBlank()) null else fill(channel.url + src, startMs, endMs, nowMs)
            src.isNotBlank() -> {
                // "default" (or unspecified with a source): the source is a full URL, or a path/query to append
                val template = if (src.startsWith("http://") || src.startsWith("https://") || src.startsWith("rtsp://")) src else channel.url + src
                fill(template, startMs, endMs, nowMs)
            }
            else -> null
        }
    }

    private fun appendQuery(url: String, q: String) = if (url.contains('?')) "$url&$q" else "$url?$q"

    /** Flussonic: http://host/stream/index.m3u8 -> http://host/stream/index-<start>-<duration>.m3u8 ; mpegts -> timeshift_abs */
    private fun flussonic(url: String, startMs: Long, endMs: Long): String? {
        val start = startMs / 1000
        val dur = ((endMs - startMs) / 1000).coerceAtLeast(60)
        val m = Regex("""^(.*/)([^/?]+?)(\.m3u8|\.ts)?(\?.*)?$""").find(url) ?: return null
        val (dir, file, ext, query) = m.destructured
        return when {
            ext == ".m3u8" -> "$dir${file.replace("mono", "index").replace("tracks-v1a1/", "")}-$start-$dur.m3u8$query"
            ext == ".ts" || file == "mpegts" -> "${dir}timeshift_abs-$start.ts$query"
            else -> "$dir$file/timeshift_abs-$start.ts$query"
        }
    }

    private val tokenFormat = Regex("""\$\{(start|end|timestamp):([^}]+)}""")

    fun fill(template: String, startMs: Long, endMs: Long, nowMs: Long): String {
        val start = startMs / 1000
        val end = endMs / 1000
        val now = nowMs / 1000
        val dur = (end - start).coerceAtLeast(0)
        val utc = TimeZone.getTimeZone("UTC")
        fun f(pattern: String, t: Long, tz: TimeZone = utc) = SimpleDateFormat(pattern, Locale.US).apply { timeZone = tz }.format(Date(t * 1000))
        var s = tokenFormat.replace(template) { m ->
            val t = when (m.groupValues[1]) { "end" -> end; "timestamp" -> now; else -> start }
            runCatching { f(m.groupValues[2], t, TimeZone.getDefault()) }.getOrDefault(m.value)
        }
        val map = linkedMapOf(
            "{utcend}" to end.toString(), "{endutc}" to end.toString(),
            "{utc}" to start.toString(), "{start}" to start.toString(),
            "{end}" to end.toString(), "{lutc}" to now.toString(), "{now}" to now.toString(),
            "{timestamp}" to now.toString(), "{offset}" to (now - start).toString(),
            "{duration}" to dur.toString(), "{duration:60}" to (dur / 60).toString(),
            "{Y}" to f("yyyy", start), "{m}" to f("MM", start), "{d}" to f("dd", start),
            "{H}" to f("HH", start), "{M}" to f("mm", start), "{S}" to f("ss", start),
            "\${start}" to start.toString(), "\${end}" to end.toString(), "\${timestamp}" to now.toString(),
            "\${duration}" to dur.toString(), "\${offset}" to (now - start).toString(),
        )
        for ((k, v) in map) s = s.replace(k, v)
        return s
    }
}
