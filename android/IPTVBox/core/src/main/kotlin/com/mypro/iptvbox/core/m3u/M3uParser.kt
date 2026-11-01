package com.mypro.iptvbox.core.m3u

import com.mypro.iptvbox.core.model.Channel
import com.mypro.iptvbox.core.model.ContentType
import com.mypro.iptvbox.core.model.PlaylistContent
import com.mypro.iptvbox.core.model.SeriesItem
import com.mypro.iptvbox.core.model.VodItem
import com.mypro.iptvbox.core.util.Ids
import java.io.BufferedReader
import java.io.InputStream
import java.io.Reader

/**
 * Parser for extended M3U / M3U8 IPTV playlists.
 *
 * Handles: #EXTM3U header attributes (url-tvg, x-tvg-url, catchup), #EXTINF attributes in
 * either quoting style, #EXTGRP, #EXTVLCOPT (http-user-agent, http-referrer), #KODIPROP,
 * blank lines, BOM, CRLF and entries without a group.
 */
object M3uParser {

    data class Result(val content: PlaylistContent, val warnings: List<String>)

    private val attrRegex = Regex("""([a-zA-Z0-9_\-]+)\s*=\s*("([^"]*)"|'([^']*)'|([^\s,]+))""")

    fun parse(stream: InputStream, playlistId: String): Result =
        parse(stream.bufferedReader(Charsets.UTF_8), playlistId)

    fun parse(text: String, playlistId: String): Result = parse(text.reader(), playlistId)

    fun parse(reader: Reader, playlistId: String): Result {
        val br = if (reader is BufferedReader) reader else BufferedReader(reader, 1 shl 16)
        val channels = ArrayList<Channel>(2048)
        val movies = ArrayList<VodItem>()
        val series = LinkedHashMap<String, SeriesItem>()
        val warnings = ArrayList<String>()
        var epgUrl = ""
        var defaultCatchupDays = 0
        var defaultCatchupSource = ""

        var pendingInf: String? = null
        var pendingGroup = ""
        val pendingOpts = LinkedHashMap<String, String>()
        var lineNo = 0
        var liveNumber = 0

        fun flush(url: String) {
            val inf = pendingInf ?: return
            pendingInf = null
            val commaIdx = findTitleComma(inf)
            val attrPart = if (commaIdx >= 0) inf.substring(0, commaIdx) else inf
            val title = if (commaIdx >= 0) inf.substring(commaIdx + 1).trim() else ""
            val attrs = parseAttributes(attrPart)
            val name = title.ifBlank { attrs["tvg-name"] ?: url.substringAfterLast('/') }
            val group = attrs["group-title"]?.takeIf { it.isNotBlank() } ?: pendingGroup
            val logo = attrs["tvg-logo"] ?: attrs["logo"] ?: ""
            val tvgId = attrs["tvg-id"] ?: ""
            val type = classify(url, group, attrs)
            val headers = buildString {
                pendingOpts["http-user-agent"]?.let { append("User-Agent=").append(it).append('\n') }
                pendingOpts["http-referrer"]?.let { append("Referer=").append(it).append('\n') }
                pendingOpts["http-origin"]?.let { append("Origin=").append(it).append('\n') }
            }.trim()
            val catchupDays = (attrs["catchup-days"] ?: attrs["tvg-rec"])?.toIntOrNull()
                ?: if (attrs["catchup"].isNullOrBlank()) defaultCatchupDays else 1
            val catchupSource = attrs["catchup-source"] ?: defaultCatchupSource
            when (type) {
                ContentType.LIVE -> {
                    liveNumber++
                    val number = attrs["tvg-chno"]?.toIntOrNull() ?: attrs["channel-number"]?.toIntOrNull() ?: liveNumber
                    channels += Channel(
                        id = Ids.stable(playlistId, url, name),
                        playlistId = playlistId,
                        name = name,
                        url = url,
                        group = group,
                        logo = logo,
                        tvgId = tvgId,
                        tvgName = attrs["tvg-name"] ?: "",
                        number = number,
                        type = ContentType.LIVE,
                        catchupDays = catchupDays,
                        catchupSource = catchupSource,
                        headers = headers,
                        extension = url.substringAfterLast('.', "").take(5),
                        country = attrs["tvg-country"] ?: "",
                        language = attrs["tvg-language"] ?: "",
                    )
                }
                ContentType.MOVIE -> movies += VodItem(
                    id = Ids.stable(playlistId, url, name),
                    playlistId = playlistId,
                    name = name,
                    url = url,
                    group = group,
                    poster = logo,
                    extension = url.substringAfterLast('.', "").take(5),
                )
                ContentType.SERIES -> {
                    // M3U series are flat: "Show S01 E02". We keep them as movies grouped under
                    // the show name so they remain playable; the Xtream API gives real seasons.
                    movies += VodItem(
                        id = Ids.stable(playlistId, url, name),
                        playlistId = playlistId,
                        name = name,
                        url = url,
                        group = group,
                        poster = logo,
                        extension = url.substringAfterLast('.', "").take(5),
                    )
                }
            }
            pendingOpts.clear()
        }

        while (true) {
            var line = br.readLine() ?: break
            lineNo++
            if (lineNo == 1 && line.isNotEmpty() && line[0] == '﻿') line = line.substring(1)
            line = line.trim()
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXTM3U") -> {
                    val attrs = parseAttributes(line.removePrefix("#EXTM3U"))
                    epgUrl = attrs["url-tvg"] ?: attrs["x-tvg-url"] ?: attrs["tvg-url"] ?: ""
                    defaultCatchupDays = attrs["catchup-days"]?.toIntOrNull() ?: 0
                    defaultCatchupSource = attrs["catchup-source"] ?: ""
                    if (defaultCatchupDays == 0 && !attrs["catchup"].isNullOrBlank()) defaultCatchupDays = 1
                }
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    if (pendingInf != null) warnings += "Line $lineNo: #EXTINF without URL was skipped"
                    pendingInf = line.substring(7).trimStart(':', ' ')
                }
                line.startsWith("#EXTGRP:", ignoreCase = true) -> pendingGroup = line.substring(8).trim()
                line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                    val kv = line.substring(11)
                    val eq = kv.indexOf('=')
                    if (eq > 0) pendingOpts[kv.substring(0, eq).trim().lowercase()] = kv.substring(eq + 1).trim()
                }
                line.startsWith("#KODIPROP:", ignoreCase = true) -> {
                    val kv = line.substring(10)
                    val eq = kv.indexOf('=')
                    if (eq > 0) pendingOpts[kv.substring(0, eq).trim().lowercase()] = kv.substring(eq + 1).trim()
                }
                line.startsWith("#") -> { /* comment or unknown directive */ }
                else -> {
                    if (pendingInf == null) {
                        // Bare URL without EXTINF: still make it playable.
                        pendingInf = "-1," + line.substringAfterLast('/').substringBefore('?')
                    }
                    flush(line)
                }
            }
        }
        if (pendingInf != null) warnings += "Playlist ended after #EXTINF without a URL"
        if (channels.isEmpty() && movies.isEmpty()) warnings += "No entries found: is this really an M3U playlist?"
        return Result(
            PlaylistContent(
                playlistId = playlistId,
                channels = channels,
                movies = movies,
                series = series.values.toList(),
                epgUrl = epgUrl,
                loadedAt = System.currentTimeMillis(),
            ),
            warnings,
        )
    }

    /** Finds the comma that separates attributes from the title (ignores commas inside quotes). */
    internal fun findTitleComma(inf: String): Int {
        var inQuote: Char? = null
        for (i in inf.indices) {
            val c = inf[i]
            if (inQuote != null) {
                if (c == inQuote) inQuote = null
            } else when (c) {
                '"', '\'' -> inQuote = c
                ',' -> return i
            }
        }
        return -1
    }

    internal fun parseAttributes(s: String): Map<String, String> {
        val out = HashMap<String, String>()
        for (m in attrRegex.findAll(s)) {
            val key = m.groupValues[1].lowercase()
            val value = m.groupValues[3].ifEmpty { m.groupValues[4].ifEmpty { m.groupValues[5] } }
            out[key] = value.trim()
        }
        return out
    }

    private val movieExt = setOf("mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v", "mpg", "mpeg", "3gp", "ts")

    internal fun classify(url: String, group: String, attrs: Map<String, String>): ContentType {
        val lowerUrl = url.lowercase()
        val path = lowerUrl.substringBefore('?')
        val g = group.lowercase()
        if (path.contains("/movie/") || path.contains("/movies/")) return ContentType.MOVIE
        if (path.contains("/series/")) return ContentType.SERIES
        if (path.contains("/live/")) return ContentType.LIVE
        val ext = path.substringAfterLast('/').substringAfterLast('.', "")
        if (ext in movieExt && ext != "ts") {
            return if (g.contains("series") || Regex("""s\d{1,2}\s?e\d{1,3}""").containsMatchIn(lowerUrl) ||
                Regex("""s\d{1,2}\s?e\d{1,3}""", RegexOption.IGNORE_CASE).containsMatchIn(attrs["tvg-name"] ?: "")
            ) ContentType.SERIES else ContentType.MOVIE
        }
        if (g.startsWith("vod") || g.contains("movie") || g.contains("film")) {
            // A "VOD" group that streams .ts/.m3u8 is still a live-style stream for the player.
            return if (ext == "ts" || ext == "m3u8") ContentType.LIVE else ContentType.MOVIE
        }
        return ContentType.LIVE
    }
}
