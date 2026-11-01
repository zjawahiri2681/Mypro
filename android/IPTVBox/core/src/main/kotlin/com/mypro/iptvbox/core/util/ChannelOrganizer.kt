package com.mypro.iptvbox.core.util

import com.mypro.iptvbox.core.model.Channel

enum class GroupMode(val label: String) { PROVIDER("Provider groups"), COUNTRY("Country"), LANGUAGE("Language") }
enum class SortMode(val label: String) { NUMBER("Channel number"), NAME("Name A–Z") }

/**
 * Groups and sorts a channel list by provider group, detected country or language.
 * Detection runs once per channel list; results are cached by list identity.
 */
class ChannelOrganizer(val channels: List<Channel>) {
    data class Group(val key: String, val label: String, val count: Int)

    private val country: Map<String, Regions.Country?> = channels.associate { it.id to Regions.detectCountry(it) }
    private val language: Map<String, String> = channels.associate { ch -> ch.id to (Regions.detectLanguage(ch, country[ch.id]) ?: OTHER) }

    fun countryOf(ch: Channel): Regions.Country? = country[ch.id]
    fun languageOf(ch: Channel): String = language[ch.id] ?: OTHER

    fun keyOf(ch: Channel, mode: GroupMode): String = when (mode) {
        GroupMode.PROVIDER -> ch.group.ifBlank { OTHER }
        GroupMode.COUNTRY -> country[ch.id]?.let { "${it.flag} ${it.name}" } ?: OTHER
        GroupMode.LANGUAGE -> language[ch.id] ?: OTHER
    }

    /** Groups ordered by size (country/language) or playlist order (provider), "Other" last. */
    fun groups(mode: GroupMode): List<Group> {
        val counts = LinkedHashMap<String, Int>()
        for (ch in channels) counts.merge(keyOf(ch, mode), 1, Int::plus)
        val list = counts.map { (k, v) -> Group(k, k, v) }
        val (other, named) = list.partition { it.key == OTHER }
        val ordered = if (mode == GroupMode.PROVIDER) named else named.sortedWith(compareByDescending<Group> { it.count }.thenBy { it.label })
        return ordered + other
    }

    fun channelsIn(mode: GroupMode, key: String, sort: SortMode = SortMode.NUMBER): List<Channel> =
        sort(channels.filter { keyOf(it, mode) == key }, sort)

    companion object {
        const val OTHER = "Other"

        fun sort(list: List<Channel>, sort: SortMode): List<Channel> = when (sort) {
            SortMode.NUMBER -> list.sortedBy { it.number }
            SortMode.NAME -> list.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name.trimStart('|', ' ', '[', '(') })
        }

        @Volatile private var cache: Pair<List<Channel>, ChannelOrganizer>? = null

        /** Memoised by list identity so UI code can call this freely. */
        fun of(channels: List<Channel>): ChannelOrganizer {
            cache?.let { (l, o) -> if (l === channels) return o }
            val o = ChannelOrganizer(channels)
            cache = channels to o
            return o
        }
    }
}
