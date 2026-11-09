package com.mypro.iptvbox.core

import com.mypro.iptvbox.core.epg.Catchup
import com.mypro.iptvbox.core.epg.XmltvParser
import com.mypro.iptvbox.core.m3u.M3uParser
import com.mypro.iptvbox.core.model.Channel
import com.mypro.iptvbox.core.net.Http
import com.mypro.iptvbox.core.sync.ReplayGuard
import com.mypro.iptvbox.core.sync.SyncMessage
import com.mypro.iptvbox.core.xtream.XtreamApi
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReviewFixesTest {
    @Test
    fun duplicateEntriesGetUniqueButStableIds() {
        val m3u = "#EXTM3U\n#EXTINF:-1 group-title=\"Sports\",Sky\nhttp://x/1.ts\n#EXTINF:-1 group-title=\"UK\",Sky\nhttp://x/1.ts\n"
        val a = M3uParser.parse(m3u, "p").content.channels
        val b = M3uParser.parse(m3u, "p").content.channels
        assertEquals(2, a.map { it.id }.distinct().size)
        assertEquals(a.map { it.id }, b.map { it.id })
    }

    @Test
    fun xtreamCatchupUsesServerTimezone() {
        val start = XmltvParser.parseTime("20240131200000 +0000")
        val url = XtreamApi.buildCatchupUrl("http://h:80", "u", "p", "42", start, 3_600_000, "Europe/Amsterdam")
        assertEquals("http://h:80/timeshift/u/p/60/2024-01-31:21-00/42.ts", url)
        assertTrue(XtreamApi.buildCatchupUrl("http://h:80", "u", "p", "42", start, 3_600_000, "").contains("2024-01-31:20-00"))
    }

    @Test
    fun m3uCatchupModes() {
        val start = 1_706_731_200_000L; val end = start + 3_600_000; val now = start + 7_200_000
        val base = Channel("1", "p", "C", "http://h/live/ch1.m3u8", catchupDays = 3)
        assertEquals("http://h/live/ch1.m3u8?utc=1706731200&lutc=1706738400", Catchup.buildUrl(base.copy(catchupType = "shift"), start, end, now))
        assertEquals("http://h/live/ch1.m3u8?start=1706731200&end=1706734800", Catchup.buildUrl(base.copy(catchupType = "append", catchupSource = "?start={utc}&end={utcend}"), start, end, now))
        assertEquals("http://h/arch/2024/01/31/20-00?d=3600", Catchup.buildUrl(base.copy(catchupType = "default", catchupSource = "http://h/arch/{Y}/{m}/{d}/{H}-{M}?d={duration}"), start, end, now))
        assertEquals("http://h/live/ch1-1706731200-3600.m3u8", Catchup.buildUrl(base.copy(catchupType = "flussonic"), start, end, now))
        assertNull(Catchup.buildUrl(base.copy(catchupType = "append"), start, end, now))
    }

    @Test
    fun xmltvKeepsFirstTitleAndReadsCompactOffsets() {
        val xml = """<tv><programme start="20240131200000+0100" stop="20240131210000+0100" channel="a"><title lang="en">News</title><title lang="ar">أخبار</title></programme></tv>"""
        val r = XmltvParser.parse(ByteArrayInputStream(xml.toByteArray()), 0, Long.MAX_VALUE)
        assertEquals("News", r.programmes.single().title)
        assertEquals(XmltvParser.parseTime("20240131200000 +0100"), r.programmes.single().start)
    }

    @Test
    fun replayGuardDropsRepeats() {
        val g = ReplayGuard()
        val m = SyncMessage(SyncMessage.Type.CMD, "a", 1L, seq = 5)
        assertTrue(g.accept(m))
        assertFalse(g.accept(m))
        assertFalse(g.accept(m.copy(seq = 4)))
        assertTrue(g.accept(m.copy(seq = 6)))
        assertTrue(g.accept(m.copy(from = "b", seq = 1)))
        assertFalse(g.accept(m.copy(from = "c", seq = 0)))
    }

    @Test
    fun redactsCredentials() {
        assertEquals("http://h/get.php?username=***&password=***&type=m3u", Http.redact("http://h/get.php?username=bob&password=s3cret&type=m3u"))
        assertEquals("http://h/live/***/***/1.ts", Http.redact("http://h/live/bob/s3cret/1.ts"))
    }
}
