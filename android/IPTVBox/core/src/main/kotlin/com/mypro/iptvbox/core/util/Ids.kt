package com.mypro.iptvbox.core.util

import java.security.MessageDigest
import java.util.UUID

object Ids {
    private val HEX = "0123456789abcdef".toCharArray()
    fun random(): String = UUID.randomUUID().toString().replace("-", "").take(16)

    /** Stable id for an item so favourites survive playlist refreshes. */
    fun stable(vararg parts: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        for (p in parts) {
            md.update(p.toByteArray(Charsets.UTF_8))
            md.update(0)
        }
        val d = md.digest()
        val sb = StringBuilder(24)
        for (i in 0 until 12) { val v = d[i].toInt() and 0xff; sb.append(HEX[v ushr 4]).append(HEX[v and 0xf]) }
        return sb.toString()
    }
}

object TextUtil {
    private val nonWord = Regex("""[^\p{L}\p{N}]+""")
    private val marks = Regex("""\p{M}+""")
    private val regionPrefix = Regex("""^\s*[\[|(]?\s*[A-Za-z]{2,3}\s*[\]|)]?\s*[:|\-]\s*""")
    private val qualityWords = Regex("""\b(uhd|fhd|hd|sd|4k|8k|h265|hevc|raw|backup|vip)\b""", RegexOption.IGNORE_CASE)

    /** Lower-cased, accent/punctuation-insensitive key for searching and EPG name matching. */
    fun normalize(s: String): String {
        val n = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
            .replace(marks, "")
        return nonWord.replace(n.lowercase(), " ").trim()
    }

    /** Strips common prefixes like "UK:", "|FR|", "HD", "FHD" for fuzzy channel matching. */
    fun channelKey(name: String): String {
        var s = name
        s = s.replace(regionPrefix, "")
        s = s.replace(qualityWords, "")
        return normalize(s)
    }

    fun formatDuration(ms: Long): String {
        if (ms <= 0) return "0:00"
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%d:%02d", m, s)
    }
}
