package com.mypro.iptvbox.core.epg

import com.mypro.iptvbox.core.model.Channel
import com.mypro.iptvbox.core.model.Programme
import com.mypro.iptvbox.core.util.TextUtil

/** In-memory EPG lookup. Thread-safe for reads after construction. */
class EpgIndex(programmes: List<Programme>, channelNames: Map<String, List<String>> = emptyMap()) {

    private val byChannel: Map<String, List<Programme>> = programmes.groupBy { it.channelId }
    private val nameToId: Map<String, String> = buildMap {
        for ((id, names) in channelNames) {
            for (n in names) {
                putIfAbsent(TextUtil.normalize(n), id)
                putIfAbsent(TextUtil.channelKey(n), id)
            }
        }
        for (id in byChannel.keys) putIfAbsent(TextUtil.normalize(id), id)
    }

    val isEmpty get() = byChannel.isEmpty()
    val channelCount get() = byChannel.size
    val programmeCount get() = byChannel.values.sumOf { it.size }

    /** Resolves the XMLTV id for a playlist channel using tvg-id, then tvg-name, then the display name. */
    fun resolveId(channel: Channel): String? {
        if (channel.tvgId.isNotBlank()) {
            if (byChannel.containsKey(channel.tvgId)) return channel.tvgId
            nameToId[TextUtil.normalize(channel.tvgId)]?.let { return it }
        }
        if (channel.tvgName.isNotBlank()) {
            nameToId[TextUtil.normalize(channel.tvgName)]?.let { return it }
            nameToId[TextUtil.channelKey(channel.tvgName)]?.let { return it }
        }
        nameToId[TextUtil.normalize(channel.name)]?.let { return it }
        nameToId[TextUtil.channelKey(channel.name)]?.let { return it }
        return null
    }

    fun programmesFor(channel: Channel): List<Programme> = resolveId(channel)?.let { byChannel[it] } ?: emptyList()

    fun nowNext(channel: Channel, now: Long = System.currentTimeMillis()): Pair<Programme?, Programme?> {
        val list = programmesFor(channel)
        if (list.isEmpty()) return null to null
        val idx = indexAt(list, now)
        val current = if (idx >= 0) list[idx] else null
        val next = when {
            idx >= 0 -> list.getOrNull(idx + 1)
            else -> list.firstOrNull { it.start > now }
        }
        return current to next
    }

    /** Programmes in [from, to] for a channel. */
    fun between(channel: Channel, from: Long, to: Long): List<Programme> =
        programmesFor(channel).filter { it.stop > from && it.start < to }

    private fun indexAt(list: List<Programme>, t: Long): Int {
        var lo = 0; var hi = list.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val p = list[mid]
            when {
                t < p.start -> hi = mid - 1
                t >= p.stop -> lo = mid + 1
                else -> return mid
            }
        }
        return -1
    }

    companion object {
        val EMPTY = EpgIndex(emptyList())
    }
}
