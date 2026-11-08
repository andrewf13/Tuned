package com.andrewf13.tuned.remote

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.andrewf13.tuned.remote.sports.Broadcast
import com.andrewf13.tuned.remote.sports.EventBuilder
import com.andrewf13.tuned.remote.sports.Listing
import com.andrewf13.tuned.remote.sports.SportsDb
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Pretend TheSportsDB, answering in its documented shape (v2 "filter", v1 "tvevents", UTC times). */
internal class FakeSportsDb(private val now: Long) : Dispatcher() {
    val requests = mutableListOf<RecordedRequest>()
    private fun ts(offsetMin: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(now + offsetMin * 60_000)
    private fun row(id: String, event: String, sport: String, channel: String, country: String, offsetMin: Long) =
        """{"id":"1","idEvent":"$id","strEvent":"$event","strSport":"$sport","strChannel":"$channel","strCountry":"$country",
           "strTimeStamp":"${ts(offsetMin)}","dateEvent":"","strTime":"","strLogo":"","strEventThumb":"","idChannel":"9"}"""

    private val bySport = mapOf(
        "Cricket" to listOf(
            row("e1", "Australia vs England", "Cricket", "Fox Cricket", "Australia", -120),
            row("e1", "Australia vs England", "Cricket", "Sky Sports Cricket", "United Kingdom", -120),
            row("e1", "Australia vs England", "Cricket", "Willow", "United States", -120),
            row("e1", "Australia vs England", "Cricket", "Star Sports 1", "India", -120),
        ),
        "Motorsport" to listOf(
            row("e2", "Formula 1 Las Vegas Grand Prix", "Motorsport", "Kayo Sports", "Australia", 300),
            row("e2", "Formula 1 Las Vegas Grand Prix", "Motorsport", "Sky Sports F1", "United Kingdom", 300),
            row("e2", "Formula 1 Las Vegas Grand Prix", "Motorsport", "TSN 1", "Canada", 300),
            row("e3", "MotoGP Valencia Grand Prix", "Motorsport", "Fox Sports 503", "Australia", -26 * 60), // finished
        ),
        "Rugby" to listOf(
            row("e4", "New Zealand vs Ireland", "Rugby", "Sky Sport 1", "New Zealand", 26 * 60),
            row("e4", "New Zealand vs Ireland", "Rugby", "Stan Sport", "Australia", 26 * 60),
        ),
        "Australian Football" to listOf(row("e5", "AFLW Grand Final", "Australian Football", "Fox Footy", "Australia", 50 * 60)),
    )

    override fun dispatch(request: RecordedRequest): MockResponse {
        requests += request
        val path = request.path!!
        return when {
            path.startsWith("/api/v2/json/filter/tv/sport/") -> {
                if (request.getHeader("X-API-KEY") != "premium") return MockResponse().setResponseCode(400)
                val sport = java.net.URLDecoder.decode(path.substringAfterLast('/'), "UTF-8")
                MockResponse().setBody("""{"filter":[${bySport[sport].orEmpty().joinToString(",")}]}""")
            }
            path.startsWith("/api/v1/json/123/eventstv.php") -> {
                val sport = java.net.URLDecoder.decode(path.substringAfter("s="), "UTF-8").replace('_', ' ')
                MockResponse().setBody("""{"tvevents":[${bySport[sport].orEmpty().take(1).joinToString(",")}]}""")
            }
            else -> MockResponse().setResponseCode(404)
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
class SportsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val now = System.currentTimeMillis()
    private val tv = FakeTv()
    private val sportsDb = FakeSportsDb(now)
    private val tvServer = MockWebServer().apply { dispatcher = tv }
    private val dbServer = MockWebServer().apply { dispatcher = sportsDb }

    @Before fun start() { tvServer.start(); dbServer.start() }
    @After fun stop() { tvServer.shutdown(); dbServer.shutdown() }

    private fun idle(cond: () -> Boolean) {
        repeat(300) { ShadowLooper.idleMainLooper(); runCatching { compose.waitForIdle() }; if (cond()) return; Thread.sleep(20) }
        error("condition never met")
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        val v = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        File("build/screenshots").apply { mkdirs() }.resolve("$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun parsesDocumentedFieldsAndUtcTimes() {
        val json = Json.parseToJsonElement(
            """{"filter":[{"idEvent":"7","strEvent":"Swans vs Giants","strSport":"Australian Football","strChannel":"Fox Footy","strCountry":"Australia","strTimeStamp":"2026-10-10 11:30:00","strLogo":"","strEventThumb":null}]}""",
        ).jsonObject
        val l = SportsDb("x").parse(json, "filter").single()
        assertEquals("Fox Footy", l.channel)
        assertEquals(SportsDb.parseUtc("2026-10-10 11:30:00"), l.startMs)
        assertEquals(1791631800000L, l.startMs) // 11:30 UTC
        assertNull(l.thumb)
        assertTrue(SportsDb("x").parse(null, "filter").isEmpty()) // v1 sometimes sends an empty body
    }

    @Test fun buildsEventsForChosenCountries() {
        fun l(id: String, ch: String, c: String, start: Long, event: String = "Match $id") = Listing(id, event, "Cricket", ch, c, start, null, null)
        val rows = listOf(
            l("a", "Fox Cricket", "Australia", now - 3_600_000), l("a", "Star Sports 1", "India", now - 3_600_000),
            l("b", "Star Sports 1", "India", now + 3_600_000),              // only Indian channels: dropped
            l("c", "Fox Cricket", "Australia", now - 9 * 3_600_000),        // finished (cricket ≈ 7 h)
            l("d", "Sky Sports Cricket", "United Kingdom", now + 7_200_000, "The Ashes: 2nd Test"),
        )
        val ev = EventBuilder.build(rows, setOf("Australia", "United Kingdom"), listOf("Ashes"), now)
        assertEquals(listOf("a", "d"), ev.map { it.id })
        assertEquals(listOf(Broadcast("Fox Cricket", "Australia", null)), ev[0].broadcasts)
        assertTrue(ev[0].isLive(now))
        assertTrue(ev[1].followed && !ev[0].followed)
    }

    private fun vm(key: String): RemoteViewModel {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val store = RemoteStore(app)
        store.tv = SavedTv("Tuned on SHIELD Android TV", "127.0.0.1", tvServer.port, "t0k3n")
        store.sportsKey = key
        store.sports = setOf("Cricket", "Motorsport", "Rugby", "Australian Football")
        store.sportsCountries = setOf("Australia", "New Zealand", "United Kingdom", "Canada")
        store.sportsKeywords = listOf("Formula 1", "All Blacks", "New Zealand")
        store.channelPicks = emptyMap()
        val vm = RemoteViewModel(app)
        vm.sportsBase = "http://127.0.0.1:${dbServer.port}"
        idle { vm.lineup.channels.isNotEmpty() }
        return vm
    }

    @Test fun sportsGuideMatchesChannelsAndChangesChannel() {
        val vm = vm("premium")
        compose.runOnUiThread { vm.tab = "sports" }
        compose.setContent { RemoteTheme("light") { RemoteApp(vm) } }
        idle { vm.sports.events.size == 4 && vm.sports.matches.size >= 7 }
        val s = vm.sports
        // MotoGP finished yesterday; the US/Indian channels are outside the chosen countries.
        assertEquals(listOf("Australia vs England", "Formula 1 Las Vegas Grand Prix", "New Zealand vs Ireland", "AFLW Grand Final"), s.events.map { it.title })
        val cricket = s.events[0]
        assertEquals(listOf("Fox Cricket", "Sky Sports Cricket"), cricket.broadcasts.map { it.channel })
        assertEquals("AU| FOX CRICKET FHD", s.resolve(cricket.broadcasts[0])?.channel?.name)
        val f1 = s.events[1]
        assertNull(s.resolve(f1.broadcasts.first { it.channel == "Kayo Sports" })) // a streaming service, no TV channel
        assertTrue(f1.followed)
        shot("sports-phone")

        compose.runOnUiThread { s.watch(cricket.broadcasts[0]) }
        idle { tv.plays.contains(102) }

        // Choose a channel yourself for an unmatched broadcaster; it's remembered.
        val kayo = f1.broadcasts.first { it.channel == "Kayo Sports" }
        compose.runOnUiThread { s.pick(kayo, vm.lineup.channels.first { it.id == 112 }) }
        assertEquals(112, s.resolve(kayo)?.channel?.id)
        assertEquals(112, RemoteStore(ApplicationProvider.getApplicationContext()).channelPicks[kayo.key])
        compose.runOnUiThread { s.filter = "followed" }
        shot("sports-followed")
    }

    @Test fun freeKeyUsesV1() {
        val vm = vm(SportsDb.FREE_KEY)
        compose.runOnUiThread { vm.tab = "sports" }
        compose.setContent { RemoteTheme("light") { RemoteApp(vm) } }
        idle { vm.sports.events.isNotEmpty() && !vm.sports.loading }
        assertTrue(sportsDb.requests.all { it.path!!.startsWith("/api/v1/json/123/eventstv.php?d=") })
        assertTrue(vm.sports.usingFreeKey)
    }

    @Test fun badKeySaysSo() {
        val vm = vm("wrong")
        compose.runOnUiThread { vm.tab = "sports" }
        compose.setContent { RemoteTheme("light") { RemoteApp(vm) } }
        idle { vm.sports.problem != null }
        assertEquals("TheSportsDB didn't accept the key", vm.sports.problem)
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-xhdpi")
class SportsTabletTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val tvServer = MockWebServer().apply { dispatcher = FakeTv() }
    private val dbServer = MockWebServer().apply { dispatcher = FakeSportsDb(System.currentTimeMillis()) }
    @Before fun start() { tvServer.start(); dbServer.start() }
    @After fun stop() { tvServer.shutdown(); dbServer.shutdown() }

    @Test fun tablet() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        RemoteStore(app).apply {
            tv = SavedTv("Tuned on SHIELD Android TV", "127.0.0.1", tvServer.port, "t0k3n")
            sportsKey = "premium"; sports = setOf("Cricket", "Motorsport", "Rugby", "Australian Football")
            sportsCountries = setOf("Australia", "New Zealand", "United Kingdom", "Canada"); sportsKeywords = listOf("Formula 1")
        }
        val vm = RemoteViewModel(app)
        vm.sportsBase = "http://127.0.0.1:${dbServer.port}"
        compose.runOnUiThread { vm.tab = "sports" }
        compose.setContent { RemoteTheme("dark") { RemoteApp(vm) } }
        repeat(300) { ShadowLooper.idleMainLooper(); runCatching { compose.waitForIdle() }; if (vm.sports.events.size == 4 && vm.sports.matches.size >= 7) return@repeat; Thread.sleep(20) }
        compose.waitForIdle()
        val v = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        File("build/screenshots").apply { mkdirs() }.resolve("sports-tablet.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertEquals(4, vm.sports.events.size)
    }
}
