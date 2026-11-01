package com.mypro.iptvbox.core

import com.mypro.iptvbox.core.epg.EpgIndex
import com.mypro.iptvbox.core.epg.XmltvParser
import com.mypro.iptvbox.core.model.Channel
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class XmltvParserTest {
    private val xml = """<?xml version="1.0" encoding="UTF-8"?>
        <tv generator-info-name="test">
          <channel id="bbc1.uk"><display-name>BBC One HD</display-name><display-name>BBC1</display-name><icon src="http://i/bbc1.png"/></channel>
          <channel id="itv.uk"><display-name>ITV</display-name></channel>
          <programme start="20240131200000 +0000" stop="20240131210000 +0000" channel="bbc1.uk"><title>News at Eight</title><desc>Headlines</desc><category>News</category></programme>
          <programme start="20240131210000 +0000" stop="20240131223000 +0000" channel="bbc1.uk"><title>Film</title></programme>
          <programme start="20240131200000 +0100" stop="20240131210000 +0100" channel="itv.uk"><title>Quiz</title></programme>
        </tv>
    """.trimIndent()

    private val t20 = XmltvParser.parseTime("20240131200000 +0000")

    @Test
    fun parsesTimesWithOffsets() {
        assertEquals(1706731200000L, t20)
        assertEquals(1706731200000L - 3600_000, XmltvParser.parseTime("20240131200000 +0100"))
        assertEquals(1706731200000L, XmltvParser.parseTime("20240131200000"))
        assertEquals(0L, XmltvParser.parseTime(null))
    }

    @Test
    fun parsesProgrammesAndNames() {
        val r = XmltvParser.parse(ByteArrayInputStream(xml.toByteArray()), windowStart = 0, windowEnd = Long.MAX_VALUE)
        assertEquals(3, r.programmes.size)
        assertEquals(listOf("BBC One HD", "BBC1"), r.channelNames["bbc1.uk"])
        assertEquals("http://i/bbc1.png", r.channelIcons["bbc1.uk"])
        val news = r.programmes.first { it.title == "News at Eight" }
        assertEquals("Headlines", news.description)
        assertEquals("News", news.category)
    }

    @Test
    fun handlesGzipAndWindow() {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(xml.toByteArray()) }
        val stream = com.mypro.iptvbox.core.net.Http.maybeGunzip(ByteArrayInputStream(bos.toByteArray()), "guide.xml.gz")
        val r = XmltvParser.parse(stream, windowStart = t20 + 61 * 60_000, windowEnd = Long.MAX_VALUE)
        // only the 21:00 film overlaps the window
        assertEquals(listOf("Film"), r.programmes.map { it.title })
    }

    @Test
    fun indexResolvesByIdNameAndFuzzyName() {
        val r = XmltvParser.parse(ByteArrayInputStream(xml.toByteArray()), windowStart = 0, windowEnd = Long.MAX_VALUE)
        val idx = EpgIndex(r.programmes, r.channelNames)
        val byId = Channel("1", "p", "Whatever", "u", tvgId = "bbc1.uk")
        val byName = Channel("2", "p", "UK: BBC One HD", "u")
        val fuzzy = Channel("3", "p", "|UK| ITV FHD", "u")
        val none = Channel("4", "p", "Unknown TV", "u")
        assertEquals("bbc1.uk", idx.resolveId(byId))
        assertEquals("bbc1.uk", idx.resolveId(byName))
        assertEquals("itv.uk", idx.resolveId(fuzzy))
        assertNull(idx.resolveId(none))

        val (now, next) = idx.nowNext(byId, t20 + 10 * 60_000)
        assertNotNull(now); assertEquals("News at Eight", now.title)
        assertNotNull(next); assertEquals("Film", next.title)
        val (gapNow, gapNext) = idx.nowNext(byId, t20 - 10 * 60_000)
        assertNull(gapNow); assertEquals("News at Eight", gapNext?.title)
    }
}
