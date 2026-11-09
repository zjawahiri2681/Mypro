package com.mypro.iptvbox.core.epg

import com.mypro.iptvbox.core.model.Programme
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.xml.parsers.SAXParserFactory

/**
 * Streaming XMLTV parser (SAX, so a 200 MB guide does not need to fit in memory).
 * Only programmes inside [windowStart, windowEnd] are kept.
 */
object XmltvParser {

    data class Result(
        val programmes: List<Programme>,
        /** XMLTV channel id -> display names (used to match playlist channels without tvg-id). */
        val channelNames: Map<String, List<String>>,
        /** XMLTV channel id -> icon URL. */
        val channelIcons: Map<String, String>,
    )

    fun parse(
        input: InputStream,
        windowStart: Long = System.currentTimeMillis() - 36L * 3600_000,
        windowEnd: Long = System.currentTimeMillis() + 7L * 24 * 3600_000,
    ): Result {
        val programmes = ArrayList<Programme>(50_000)
        val names = HashMap<String, MutableList<String>>()
        val icons = HashMap<String, String>()

        val handler = object : DefaultHandler() {
            var inProgramme = false
            var channel = ""
            var start = 0L
            var stop = 0L
            var title = StringBuilder()
            var desc = StringBuilder()
            var category = StringBuilder()
            var current: StringBuilder? = null
            var currentChannelId = ""
            var inChannel = false
            var inDisplayName = false
            val text = StringBuilder()

            override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
                when (qName) {
                    "programme" -> {
                        inProgramme = true
                        channel = attributes.getValue("channel") ?: ""
                        start = parseTime(attributes.getValue("start"))
                        stop = parseTime(attributes.getValue("stop"))
                        title.setLength(0); desc.setLength(0); category.setLength(0)
                    }
                    // Guides often repeat <title>/<desc> per language: keep the first of each.
                    "title" -> if (inProgramme && title.isEmpty()) current = title
                    "desc" -> if (inProgramme && desc.isEmpty()) current = desc
                    "category" -> if (inProgramme && category.isEmpty()) current = category
                    "channel" -> { inChannel = true; currentChannelId = attributes.getValue("id") ?: "" }
                    "display-name" -> if (inChannel) { inDisplayName = true; text.setLength(0) }
                    "icon" -> if (inChannel && currentChannelId.isNotEmpty()) {
                        attributes.getValue("src")?.let { icons.putIfAbsent(currentChannelId, it) }
                    }
                }
            }

            override fun characters(ch: CharArray, startIdx: Int, length: Int) {
                current?.append(ch, startIdx, length)
                if (inDisplayName) text.append(ch, startIdx, length)
            }

            override fun endElement(uri: String?, localName: String?, qName: String) {
                when (qName) {
                    "title", "desc", "category" -> current = null
                    "display-name" -> if (inDisplayName) {
                        inDisplayName = false
                        val n = text.toString().trim()
                        if (n.isNotEmpty()) names.getOrPut(currentChannelId) { ArrayList(2) }.add(n)
                    }
                    "channel" -> inChannel = false
                    "programme" -> {
                        inProgramme = false
                        if (stop == 0L && start != 0L) stop = start + 30 * 60_000
                        if (start != 0L && stop > windowStart && start < windowEnd) {
                            programmes += Programme(
                                channelId = channel,
                                start = start,
                                stop = stop,
                                title = title.toString().trim(),
                                description = desc.toString().trim(),
                                category = category.toString().trim(),
                            )
                        }
                    }
                }
            }
        }

        val factory = SAXParserFactory.newInstance()
        factory.isNamespaceAware = false
        factory.isValidating = false
        try { factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) } catch (_: Exception) {}
        try { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) } catch (_: Exception) {}
        val parser = factory.newSAXParser()
        parser.parse(InputSource(input), handler)
        programmes.sortWith(compareBy({ it.channelId }, { it.start }))
        return Result(programmes, names, icons)
    }

    private val tlFormat = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    }

    private val timeRegex = Regex("""^(\d{8,14})\s*([+-]\d{4})?.*$""")

    /** Parses "20240131203000 +0100" (also tolerates missing seconds/offset). */
    fun parseTime(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        val s = raw.trim()
        // "20240131200000 +0100", "20240131200000+0100" or "20240131200000"
        val m = timeRegex.find(s)
        var datePart = m?.groupValues?.get(1) ?: s.substringBefore(' ')
        val offsetPart = m?.groupValues?.get(2) ?: ""
        if (datePart.length < 14) datePart = datePart.padEnd(14, '0')
        if (datePart.length > 14) datePart = datePart.substring(0, 14)
        val base = try { tlFormat.get()!!.parse(datePart)?.time ?: return 0L } catch (e: Exception) { return 0L }
        if (offsetPart.length >= 5 && (offsetPart[0] == '+' || offsetPart[0] == '-')) {
            val sign = if (offsetPart[0] == '-') -1 else 1
            val hh = offsetPart.substring(1, 3).toIntOrNull() ?: 0
            val mm = offsetPart.substring(3, 5).toIntOrNull() ?: 0
            return base - sign * (hh * 3600_000L + mm * 60_000L)
        }
        return base
    }
}
