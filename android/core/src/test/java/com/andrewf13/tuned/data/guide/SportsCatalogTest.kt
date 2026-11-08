package com.andrewf13.tuned.data.guide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Titles taken from real epgshare01 AU / NZ / CA guides (November 2026). */
class SportsCatalogTest {
    private fun c(title: String, sub: String? = null, desc: String? = null, cats: List<String> = listOf("Sport"), ch: String = "Fox Sports", prev: Boolean = false) =
        SportsCatalog.classify(title, sub, desc, cats, ch, prev)

    @Test fun foxtel() {
        val nrl = c("NRL - R12 Bulldogs v Storm", ch = "Fox League")
        assertEquals("nrl", nrl.sport); assertTrue(nrl.event); assertFalse(nrl.live)
        assertEquals("afl", c("AFLW - North Melbourne v Carlton", ch = "Fox Footy").sport)
        val golf = c("LIVE: USPGA Tour - WWT Championship Round 3", ch = "Fox Sports 503")
        assertEquals("golf", golf.sport); assertTrue(golf.live); assertFalse(golf.replay)
        val classic = c("Test Classic - 1991/92 AUS v IND 4th Test", ch = "Fox Cricket")
        assertEquals("cricket", classic.sport); assertTrue(classic.replay)
        assertTrue(c("WBBL Mini - Stars v Sixers", ch = "Fox Cricket").replay)
        assertTrue(c("Test Wrap - RSA v AUS Third Test", ch = "Fox Cricket").replay)
        assertEquals("cricket", c("WBBL - Thunder v Heat", ch = "Fox Cricket").sport)
        // talk shows aren't events
        assertFalse(c("Fixture On Fox - Special", ch = "Fox Footy").event)
        assertFalse(c("Keepin' It Cricket - Episode 9", ch = "Fox Cricket").event)
    }

    @Test fun skyNz() {
        val f1 = c("FIA Formula One World Championship", "FIA Formula 1: Brazil Grand Prix", "LIVE coverage of FIA Formula 1, Brazil Grand Prix.", listOf("Episode", "Series"), "Sky Sport 1")
        assertEquals("f1", f1.sport); assertTrue(f1.live); assertTrue(f1.event)
        val hls = c("Rugby League World Cup", "RLWC SF1: Kiwis v Tonga Ext Hls", "Extended highlights from the Men's Rugby League World Cup", listOf("Episode"), "Sky Sport 1")
        assertEquals("nrl", hls.sport); assertTrue(hls.replay); assertFalse(hls.live)
        val union = c("Rugby: Nations Championship", "NC: England v Australia", "LIVE coverage from Round 4 of the Nations Championship", listOf("Episode", "Series"), "Sky Sport 2")
        assertEquals("rugby", union.sport); assertTrue(union.live)
        assertEquals("soccer", c("A-League: Women", "ALW: Perth Glory v Canberra Utd", "Replay coverage from Round 4", listOf("Episode"), "Sky Sport 1").sport)
        assertFalse(c("The Breakdown", null, "Fresh off the back of the weekend action", listOf("Episode"), "Sky Sport 1").event)
    }

    @Test fun canadaAndOthers() {
        val soccer = c("Live: FA Women's Super League", cats = listOf("Sports", "Soccer"), ch = "Sportsnet One HD")
        assertEquals("soccer", soccer.sport); assertTrue(soccer.live)
        val nhl = c("NHL Hockey", cats = listOf("Sports", "Hockey"), ch = "Sportsnet One HD", prev = true)
        assertEquals("icehockey", nhl.sport); assertTrue(nhl.replay)
        assertEquals("motogp", c("MotoGP Valencia Grand Prix", ch = "Fox Sports 506").sport) // not F1
        assertEquals("wrc", c("WRC - Rally Japan Stage 12").sport)
        assertEquals("tennis", c("Tennis: ATP Masters 1000", "Paris SF1", cats = listOf("Sports"), ch = "beIN Sports 1").sport)
        assertEquals(null, c("Seinfeld", cats = listOf("Comedy"), ch = "Seven").sport)
    }
}
