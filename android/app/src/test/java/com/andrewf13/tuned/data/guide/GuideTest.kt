package com.andrewf13.tuned.data.guide

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.andrewf13.tuned.data.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GuideTest {
    private var id = 1
    private fun ch(name: String, epg: String? = null, group: String = "1") = Channel(id++, id, name, null, group, epg)

    /** Named the way the user's provider names channels. */
    private val lineup = listOf(
        ch("AU| FOX CRICKET FHD"), ch("AU| FOX CRICKET HD"), ch("AU| FOX FOOTY FHD"), ch("AU| FOX LEAGUE FHD"),
        ch("AU| FOX SPORTS 503 FHD"), ch("AU| FOX SPORTS 505 FHD"), ch("AU| ESPN FHD"), ch("AU| ABC NEWS SYDNEY"),
        ch("AU| BEIN SPORTS 1 HD"), ch("NZ| SKY SPORT 1 HD"), ch("NZ| SKY SPORT 2 HD"), ch("Sky Sport 3 NZ"),
        ch("CA| TSN1 FHD", epg = "tsn1.ca"), ch("US| ESPN HD"), ch("UK: SKY SPORTS F1 HD"),
    )
    private fun named(n: String) = lineup.first { it.name == n }.streamId

    private val fixture = """<?xml version="1.0" encoding="UTF-8"?>
<tv>
  <channel id="FoxCricket.au"><display-name>Fox Cricket</display-name></channel>
  <channel id="ESPN.au"><display-name>ESPN</display-name></channel>
  <channel id="Seinfeld.au"><display-name>Comedy Channel</display-name></channel>
  <programme start="20261108000000 +0000" stop="20261108033000 +0000" channel="FoxCricket.au">
    <title>WBBL - Thunder v Heat</title><desc>The Sydney Thunder host the Brisbane Heat.</desc><category>Sport</category></programme>
  <programme start="20261108033000 +0000" stop="20261108040000 +0000" channel="FoxCricket.au">
    <title>Test Classic - 1991/92 AUS v IND 4th Test</title><category>Sport</category></programme>
  <programme start="20261108040000 +0000" stop="20261108080000 +0000" channel="FoxCricket.au">
    <title>LIVE: WBBL - Stars v Sixers</title><category>Sport</category></programme>
  <programme start="20261108160000 +1100" stop="20261108190000 +1100" channel="ESPN.au">
    <title>NFL - Chiefs v Bills</title><category>Sport</category><previously-shown/></programme>
</tv>"""

    @Test fun readsTimesWithOffsets() {
        assertEquals(1794096000000L, XmltvReader.parseTime("20261108000000 +0000"))
        assertEquals(1794096000000L - 11 * 3_600_000L, XmltvReader.parseTime("20261108000000 +1100"))
    }

    @Test fun fixtureLayeringAndSports() {
        val now = XmltvReader.parseTime("20261108050000 +0000")!!
        val kept = setOf(named("AU| FOX CRICKET FHD"))
        val loader = GuideLoader(lineup, kept, now)
        // Provider guide covers TSN; epgshare fills Fox Cricket (both variants) and ESPN AU, never ESPN US.
        loader.addProvider("""<tv><programme start="20261108040000 +0000" stop="20261108060000 +0000" channel="tsn1.ca"><title>NHL Hockey</title><sub-title>Oilers v Flames</sub-title><category>Hockey</category></programme></tv>""".byteInputStream())
        loader.addCountryGuide(fixture.byteInputStream(), GuideSources.byCode("AU")!!, "f")
        val b = loader.build()
        assertEquals("p:tsn1.ca", b.channelMap[named("CA| TSN1 FHD")])
        assertEquals("e:FoxCricket.au", b.channelMap[named("AU| FOX CRICKET FHD")])
        assertEquals("e:FoxCricket.au", b.channelMap[named("AU| FOX CRICKET HD")])
        assertEquals("e:ESPN.au", b.channelMap[named("AU| ESPN FHD")])
        assertTrue(named("US| ESPN HD") !in b.channelMap)
        assertEquals(1, b.keptCovered)

        val store = GuideStore(ApplicationProvider.getApplicationContext<Application>(), "t1")
        store.replace(b, now)
        val live = store.sports(setOf("cricket"), emptyList(), includeReplays = false, now, now + 86_400_000)
        assertEquals(listOf("LIVE: WBBL - Stars v Sixers"), live.map { it.row.title }) // replays and finished games left out
        assertEquals(2, live.single().streams.size) // both Fox Cricket channels
        assertEquals(2, store.sports(setOf("cricket"), emptyList(), includeReplays = true, now - 10 * 3_600_000, now + 86_400_000).size - 1)
        assertEquals("LIVE: WBBL - Stars v Sixers", store.upcoming(named("AU| FOX CRICKET FHD"), now).first().title)
        assertEquals("NHL Hockey: Oilers v Flames", store.upcoming(named("CA| TSN1 FHD"), now).first().title)
        assertEquals(listOf("NFL - Chiefs v Bills"), store.sports(emptySet(), listOf("Chiefs"), true, 0, Long.MAX_VALUE).map { it.row.title })
    }

    /** The real epgshare01 files (downloaded during development; skipped when absent). */
    @Test fun realAustraliaAndNewZealandGuides() {
        val dir = File(System.getProperty("tuned.epgDir") ?: "/tmp/claude-0/-home-user/b61819af-f84e-566a-93af-4885611dae50/scratchpad/epg")
        val au = File(dir, "AU1.xml.gz"); val nz = File(dir, "NZ1.xml.gz")
        assumeTrue(au.exists() && nz.exists())
        val now = XmltvReader.parseTime("20261108060000 +0000")!!
        val kept = lineup.map { it.streamId }.toSet()
        val t0 = System.currentTimeMillis()
        val loader = GuideLoader(lineup, kept, now)
        au.inputStream().use { loader.addCountryGuide(it, GuideSources.byCode("AU")!!, au.name) }
        nz.inputStream().use { loader.addCountryGuide(it, GuideSources.byCode("NZ")!!, nz.name) }
        val b = loader.build()
        val ms = System.currentTimeMillis() - t0
        val mapped = b.channelMap.mapKeys { (k, _) -> lineup.first { it.streamId == k }.name }
        println("Real AU+NZ: ${b.rows.size} programmes kept, ${mapped.size} channels mapped in ${ms} ms")
        mapped.toSortedMap().forEach { (n, k) -> println("  $n → $k") }
        listOf("AU| FOX CRICKET FHD", "AU| FOX FOOTY FHD", "AU| FOX LEAGUE FHD", "AU| FOX SPORTS 503 FHD", "AU| ESPN FHD",
            "AU| BEIN SPORTS 1 HD", "NZ| SKY SPORT 1 HD", "NZ| SKY SPORT 2 HD", "Sky Sport 3 NZ").forEach { assertTrue("$it not mapped", it in mapped) }
        assertTrue("US| ESPN HD" !in mapped && "UK: SKY SPORTS F1 HD" !in mapped)

        val store = GuideStore(ApplicationProvider.getApplicationContext<Application>(), "real")
        store.replace(b, now)
        val wanted = setOf("cricket", "afl", "nrl", "rugby", "f1", "motogp", "wrc", "nfl", "golf", "tennis")
        val found = store.sports(wanted, emptyList(), includeReplays = false, now, now + 3 * 86_400_000L)
        println("Sports found (no replays, next 3 days): ${found.size}")
        found.groupBy { it.row.sport }.forEach { (s, l) -> println("  $s: ${l.size}  e.g. " + l.take(3).joinToString(" / ") { "${if (it.row.live) "LIVE " else ""}${it.row.title}${it.row.sub?.let { s2 -> " — $s2" } ?: ""}" }) }
        assertTrue(found.any { it.row.sport == "f1" && it.row.live })
        assertTrue(found.none { it.row.replay })
    }
}
