package com.andrewf13.tuned.remote

import com.andrewf13.tuned.data.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelMatcherTest {
    private var next = 1
    private fun ch(name: String) = Channel(next++, next, name, null, "g", null)

    // Names in the style of the user's provider (from their screenshots and common IPTV naming).
    private val lineup = listOf(
        ch("CA| TSN1 FHD"), ch("CA| TSN2 FHD"), ch("CA| TSN3 FHD"), ch("CA| TSN 4 HD"), ch("CA| WILLOW CRICKET HD"),
        ch("AU| FOX CRICKET FHD"), ch("AU| FOX FOOTY FHD"), ch("AU| FOX LEAGUE HD"), ch("AU| FOX SPORTS 503 HD"),
        ch("AU| FOX SPORTS 505 HD"), ch("AU| 7MATE HD"), ch("AU| ABC NEWS SYDNEY"),
        ch("4K| SKY SPORTS MAIN EVENT UHD"), ch("UK: SKY SPORTS MAIN EVENT HD"), ch("UK: SKY SPORTS F1 HD"),
        ch("UK: SKY SPORTS CRICKET HD"), ch("UK: TNT SPORTS 1 HD"), ch("UK: TNT SPORTS 2 HD"),
        ch("Sky Sport 1 NZ"), ch("Sky Sport 2 NZ"), ch("US| ESPN HD"), ch("US| ESPN2 HD"), ch("US| FOX SPORTS 1"),
    )
    private fun best(name: String, country: String?, preferred: Set<Int> = emptySet()) =
        ChannelMatcher.match(name, country, lineup, preferred).firstOrNull()?.channel?.name

    @Test fun spacingAndQualityTagsDoNotMatter() {
        assertEquals("CA| TSN1 FHD", best("TSN 1", "Canada"))
        assertEquals("CA| TSN 4 HD", best("TSN4", "Canada"))
        assertEquals("AU| FOX CRICKET FHD", best("Fox Cricket", "Australia"))
        assertEquals("CA| WILLOW CRICKET HD", best("Willow", "Canada"))
    }

    @Test fun numbersMustMatchExactly() {
        assertEquals("AU| FOX SPORTS 503 HD", best("Fox Sports 503", "Australia"))
        assertEquals("UK: TNT SPORTS 2 HD", best("TNT Sports 2", "United Kingdom"))
        assertEquals("US| ESPN2 HD", best("ESPN2", "United States"))
        assertEquals("US| ESPN HD", best("ESPN", "United States")) // not ESPN2
        assertEquals(null, best("Fox Sports 507", "Australia"))
    }

    @Test fun countryDecidesBetweenNamesakes() {
        // "Fox Sports 1" exists in the US; an Australian listing must not pick it.
        assertEquals("US| FOX SPORTS 1", best("Fox Sports 1", "United States"))
        assertEquals(null, best("Fox Sports 1", "Australia"))
        assertEquals("Sky Sport 1 NZ", best("Sky Sport 1", "New Zealand"))
    }

    @Test fun sportsVersusSportAndPreferredTieBreak() {
        assertEquals("UK: SKY SPORTS F1 HD", best("Sky Sports F1", "United Kingdom"))
        val main = lineup.filter { it.name.contains("MAIN EVENT") }
        // Both main-event channels match; the one in the TV's ticked list wins.
        assertEquals("UK: SKY SPORTS MAIN EVENT HD", best("Sky Sports Main Event", "United Kingdom", setOf(main[1].streamId)))
        // A "4K|" prefix isn't a country, so the 4K channel is still offered.
        assertTrue(ChannelMatcher.match("Sky Sports Main Event", "United Kingdom", lineup, emptySet()).any { it.channel == main[0] })
    }

    @Test fun unrelatedChannelsDoNotMatch() {
        assertEquals(null, best("Kayo Sports", "Australia"))
        assertEquals(null, best("BT Sport 1", "United Kingdom"))
    }
}
