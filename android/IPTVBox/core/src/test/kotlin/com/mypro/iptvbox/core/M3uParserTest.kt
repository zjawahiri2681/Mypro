package com.mypro.iptvbox.core

import com.mypro.iptvbox.core.m3u.M3uParser
import com.mypro.iptvbox.core.model.ContentType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class M3uParserTest {
    private val sample = """
        ${'﻿'}#EXTM3U url-tvg="http://epg.example.com/guide.xml.gz" catchup="default" catchup-days="3"
        #EXTINF:-1 tvg-id="bbc1.uk" tvg-name="BBC One HD" tvg-logo="http://logo/bbc1.png" group-title="UK | News, Sport",UK: BBC One HD
        #EXTVLCOPT:http-user-agent=MyAgent/1.0
        http://server:8080/live/u/p/1001.ts
        #EXTINF:-1 tvg-id='itv.uk' group-title='UK',ITV
        http://server:8080/live/u/p/1002.m3u8
        #EXTGRP:Documentaries
        #EXTINF:0,Nature Channel
        http://server:8080/1003
        #EXTINF:-1 tvg-logo="http://logo/m.jpg" group-title="VOD | Action",Big Movie (2021)
        http://server:8080/movie/u/p/5001.mkv
        #EXTINF:-1 group-title="Series",Show S01 E02
        http://server:8080/series/u/p/7001.mp4

        http://server:8080/bare.ts
    """.trimIndent()

    @Test
    fun parsesHeaderAttributesAndChannels() {
        val r = M3uParser.parse(sample, "pl1")
        val c = r.content
        assertEquals("http://epg.example.com/guide.xml.gz", c.epgUrl)
        assertEquals(4, c.channels.size)
        assertEquals(2, c.movies.size)

        val bbc = c.channels[0]
        assertEquals("UK: BBC One HD", bbc.name)
        assertEquals("bbc1.uk", bbc.tvgId)
        assertEquals("UK | News, Sport", bbc.group)
        assertEquals("http://logo/bbc1.png", bbc.logo)
        assertEquals("User-Agent=MyAgent/1.0", bbc.headers)
        assertEquals(3, bbc.catchupDays)
        assertEquals(1, bbc.number)

        val itv = c.channels[1]
        assertEquals("itv.uk", itv.tvgId)
        assertEquals("UK", itv.group)
        assertEquals("", itv.headers)

        val nature = c.channels[2]
        assertEquals("Documentaries", nature.group)
        assertEquals("Nature Channel", nature.name)

        val bare = c.channels[3]
        assertEquals("bare.ts", bare.name)
        assertEquals(ContentType.LIVE, bare.type)
        assertTrue(r.warnings.isEmpty(), r.warnings.toString())
    }

    @Test
    fun classifiesMoviesAndSeries() {
        assertEquals(ContentType.MOVIE, M3uParser.classify("http://x/movie/u/p/1.mkv", "", emptyMap()))
        assertEquals(ContentType.SERIES, M3uParser.classify("http://x/series/u/p/1.mp4", "", emptyMap()))
        assertEquals(ContentType.LIVE, M3uParser.classify("http://x/live/u/p/1.ts", "VOD", emptyMap()))
        assertEquals(ContentType.LIVE, M3uParser.classify("http://x/stream.m3u8", "News", emptyMap()))
        assertEquals(ContentType.MOVIE, M3uParser.classify("http://x/files/film.mp4", "Films", emptyMap()))
    }

    @Test
    fun titleCommaIgnoresQuotedCommas() {
        val inf = """-1 tvg-name="A, B" group-title="G, H",Title, with comma"""
        val i = M3uParser.findTitleComma(inf)
        assertEquals("Title, with comma", inf.substring(i + 1))
    }

    @Test
    fun stableIdsSurviveReparse() {
        val a = M3uParser.parse(sample, "pl1").content.channels[0].id
        val b = M3uParser.parse(sample, "pl1").content.channels[0].id
        assertEquals(a, b)
    }
}
