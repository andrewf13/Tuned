package com.andrewf13.tuned.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class M3uParseTest {
    private val sample = """
        #EXTM3U x-tvg-url="http://epg/x.xml"
        #EXTINF:-1 tvg-id="fox.au" tvg-name="Fox Footy" tvg-logo="http://l/f.png" group-title="AU | Sport",Fox Footy HD
        http://p/live/u/p/1.ts
        #EXTINF:-1 tvg-chno="7" group-title="AU, Free",Seven, Sydney
        http://p/live/u/p/2.ts
        #EXTINF:-1,No Group Channel
        #EXTGRP:Misc
        http://p/3.m3u8
        #EXTINF:-1 group-title="Movies",Some Film (2024)
        http://p/movie/u/p/99.mkv
    """.trimIndent()

    @Test fun parsesChannelsGroupsAndAttributes() {
        val l = M3uParse.parse(sample)
        assertEquals(listOf("Fox Footy HD", "Seven, Sydney", "No Group Channel"), l.channels.map { it.name })
        assertEquals(listOf("AU | Sport", "AU, Free", "Misc"), l.categories.map { it.name })
        val fox = l.channels[0]
        assertEquals("http://l/f.png", fox.icon)
        assertEquals("fox.au", fox.epgId)
        assertEquals("http://p/live/u/p/1.ts", fox.url)
        assertEquals(7, l.channels[1].num)
        assertNull(l.channels[2].icon)
    }

    @Test fun moviesAreSkipped() {
        assertEquals(3, M3uParse.parse(sample).channels.size)
    }

    @Test fun idsAreStableAcrossReloads() {
        assertEquals(M3uParse.parse(sample).channels.map { it.streamId }, M3uParse.parse(sample).channels.map { it.streamId })
    }
}
