package com.andrewf13.tuned.mobile

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.AudioManager
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.andrewf13.tuned.data.Channel
import com.andrewf13.tuned.data.Prefs
import com.andrewf13.tuned.data.guide.GuideLoader
import com.andrewf13.tuned.data.guide.GuideSources
import com.andrewf13.tuned.data.guide.GuideStore
import com.andrewf13.tuned.data.guide.XmltvReader
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File

/** Seeds a provider, a saved channel list and a guide the way a real install has them. */
internal object Seed {
    private val names = listOf(
        "AU| FOX CRICKET FHD" to 1, "AU| FOX FOOTY FHD" to 1, "AU| FOX LEAGUE FHD" to 1, "AU| FOX SPORTS 506 FHD" to 1,
        "NZ| SKY SPORT 1 HD" to 2, "NZ| SKY SPORT 2 HD" to 2, "AU| ABC NEWS SYDNEY" to 3, "AU| SEVEN SYDNEY" to 3, "AU| NINE SYDNEY" to 3,
        "UK: SKY SPORTS F1 HD" to 4, "CA| TSN1 FHD" to 5, "US| ESPN HD" to 6,
    )

    fun install(now: Long) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        app.getSharedPreferences("tuned", 0).edit().clear()
            .putString("server", "http://127.0.0.1:9").putString("user", "demo").putString("pass", "demo").commit()
        app.getSharedPreferences("tuned_mobile", 0).edit().clear().commit()
        val cats = listOf("AU | Sport", "NZ | Sport", "AU | Free to air", "UK | Sport", "CA | Sport", "US | Sport")
        File(app.filesDir, "live_categories.json").writeText(cats.mapIndexed { i, n -> """{"category_id":"${i + 1}","category_name":"$n"}""" }.joinToString(",", "[", "]"))
        File(app.filesDir, "live_streams.json").writeText(names.mapIndexed { i, (n, c) -> """{"num":${i + 1},"name":"$n","stream_id":${100 + i},"category_id":"$c","stream_icon":""}""" }.joinToString(",", "[", "]"))
        Prefs(app).current.apply { favourites = setOf(100, 104); recents = listOf(104, 101, 100) }

        // A small guide in epgshare01's real format, built through the real loader.
        fun ts(min: Long) = java.text.SimpleDateFormat("yyyyMMddHHmmss", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(now + min * 60_000) + " +0000"
        fun p(ch: String, from: Long, to: Long, title: String, sub: String? = null, desc: String = "") =
            """<programme start="${ts(from)}" stop="${ts(to)}" channel="$ch"><title>$title</title>${sub?.let { "<sub-title>$it</sub-title>" } ?: ""}<desc>$desc</desc><category>Sport</category></programme>"""
        val au = """<tv><channel id="FoxCricket.au"><display-name>Fox Cricket</display-name></channel><channel id="FoxFooty.au"><display-name>Fox Footy</display-name></channel>
            <channel id="FoxLeague.au"><display-name>Fox League</display-name></channel><channel id="FoxSports506.au"><display-name>Fox Sports 506</display-name></channel>
            ${p("FoxCricket.au", -60, 120, "LIVE: WBBL - Heat v Hurricanes")}${p("FoxFooty.au", -30, 60, "AFL Mini - GF Fremantle v Brisbane")}${p("FoxFooty.au", 60, 180, "AFLW - Sydney v Western Bulldogs")}
            ${p("FoxLeague.au", 26 * 60, 28 * 60, "NRL - R13 Knights v Eels")}${p("FoxSports506.au", 300, 360, "LIVE: MotoGP - Qatar Practice 3")}</tv>"""
        val nz = """<tv><channel id="Sky.Sport.1.nz"><display-name>Sky Sport 1</display-name></channel><channel id="Sky.Sport.2.nz"><display-name>Sky Sport 2</display-name></channel>
            ${p("Sky.Sport.1.nz", 90, 240, "FIA Formula One World Championship", "FIA Formula 1: Brazil Grand Prix", "LIVE coverage of FIA Formula 1, Brazil Grand Prix.")}
            ${p("Sky.Sport.2.nz", -20, 100, "Rugby: Nations Championship", "NC: England v Australia", "LIVE coverage from Round 4")}</tv>"""
        val channels = names.mapIndexed { i, (n, c) -> Channel(100 + i, i + 1, n, null, "$c", null) }
        val loader = GuideLoader(channels, channels.map { it.streamId }.toSet(), now)
        loader.addCountryGuide(au.byteInputStream(), GuideSources.byCode("AU")!!, "au")
        loader.addCountryGuide(nz.byteInputStream(), GuideSources.byCode("NZ")!!, "nz")
        GuideStore(app, "default").apply { replace(loader.build(), now); close() }
    }
}

abstract class AppTestBase {
    @get:Rule val compose = createEmptyComposeRule()
    protected lateinit var scenario: ActivityScenario<MainActivity>
    protected lateinit var vm: MobileViewModel
    protected lateinit var activity: MainActivity

    protected fun launch() {
        Seed.install(System.currentTimeMillis())
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity = it; vm = ViewModelProvider(it, ViewModelProvider.AndroidViewModelFactory(it.application))[MobileViewModel::class.java] }
        idle { vm.view.all.isNotEmpty() }
    }

    @After fun close() { runCatching { scenario.close() } }

    protected fun idle(cond: () -> Boolean) {
        repeat(300) { ShadowLooper.idleMainLooper(); runCatching { compose.waitForIdle() }; if (cond()) return; Thread.sleep(20) }
        error("condition never met: page=${if (::vm.isInitialized) vm.page else null} all=${if (::vm.isInitialized) vm.view.all.size else -1} loading=${if (::vm.isInitialized) vm.loading else null} status=${if (::vm.isInitialized) vm.status else null} files=${ApplicationProvider.getApplicationContext<Application>().filesDir.list()?.toList()} profile=${Prefs(ApplicationProvider.getApplicationContext()).activeProfile?.id} direct=${if (::vm.isInitialized) kotlinx.coroutines.runBlocking { vm.repo.loadCached()?.channels?.size } else null} sameApp=${if (::vm.isInitialized) vm.getApplication<Application>() === ApplicationProvider.getApplicationContext<Application>() else null} vmFiles=${if (::vm.isInitialized) vm.getApplication<Application>().filesDir.absolutePath else null} testFiles=${ApplicationProvider.getApplicationContext<Application>().filesDir.absolutePath}")
    }

    protected fun shot(name: String) {
        compose.waitForIdle()
        scenario.onActivity { a ->
            val v = a.window.decorView
            val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
            v.draw(Canvas(bmp))
            File("build/screenshots").apply { mkdirs() }.resolve("$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-port-xxhdpi") // Galaxy S25, portrait
class PhoneTest : AppTestBase() {
    @Test fun liveTvAndPortraitPlayer() {
        launch()
        assertEquals(12, vm.view.all.size) // migrated login + saved list
        shot("m-live")
        compose.waitForIdle()
        scenario.onActivity { vm.chooseGroup("cat:1"); vm.play(vm.channelsFor("cat:1"), vm.view.byId[100]!!) }
        idle { vm.page == Page.Player }
        shot("m-player-portrait")
        scenario.onActivity { vm.tune(vm.view.byId[101]!!) }
        assertEquals("AU| FOX FOOTY FHD", vm.playing?.name)
    }

    @Test fun sportsTab() {
        launch()
        scenario.onActivity { vm.tab = Tab.Sports }
        Thread.sleep(300); idle { true }
        repeat(20) { ShadowLooper.idleMainLooper(); compose.waitForIdle(); Thread.sleep(50) }
        shot("m-sports")
    }

    @Test fun welcomeAndBackupRoundTrip() {
        launch()
        val app = ApplicationProvider.getApplicationContext<Application>()
        val saved = kotlinx.coroutines.runBlocking { vm.saveBackup("passphrase1") }
        assertTrue(saved.startsWith("Download/Tuned/"))
        // Robolectric's media store doesn't list files back, so take the same bytes the app writes.
        val bytes = com.andrewf13.tuned.data.BackupCrypto.encrypt(com.andrewf13.tuned.data.Backup.export(Prefs(app)), "passphrase1".toCharArray())
        scenario.onActivity { vm.deleteProfile("default") }
        idle { vm.page == Page.Welcome }
        shot("m-welcome")
        kotlinx.coroutines.runBlocking { vm.restoreBackup(bytes, "passphrase1") }
        idle { vm.page == Page.Main }
        assertEquals(setOf(100, 104), Prefs(app).current.favourites)
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w780dp-h360dp-land-xxhdpi") // Galaxy S25, landscape
class PhoneLandscapeTest : AppTestBase() {
    @Test fun fullscreenGestures() {
        launch()
        scenario.onActivity { vm.chooseGroup("all"); vm.play(vm.channelsFor("all"), vm.view.byId[100]!!) }
        idle { vm.page == Page.Player }
        shot("m-fullscreen")
        val audio = ApplicationProvider.getApplicationContext<Context>().getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, max / 4, 0)
        // Right side, drag up: louder.
        compose.onRoot().performTouchInput { swipe(Offset(width * 0.85f, height * 0.75f), Offset(width * 0.85f, height * 0.25f), 400) }
        idle { true }
        assertTrue("volume went up", audio.getStreamVolume(AudioManager.STREAM_MUSIC) > max / 4)
        // Left side, drag down: dimmer.
        compose.onRoot().performTouchInput { swipe(Offset(width * 0.15f, height * 0.25f), Offset(width * 0.15f, height * 0.75f), 400) }
        idle { true }
        var b = -1f
        scenario.onActivity { b = it.window.attributes.screenBrightness }
        assertTrue("brightness set ($b)", b in 0f..0.6f)
        // Middle, swipe left: next channel.
        compose.onRoot().performTouchInput { swipe(Offset(width * 0.65f, height * 0.5f), Offset(width * 0.35f, height * 0.5f), 300) }
        idle { vm.playing?.streamId == 101 }
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-xhdpi") // Galaxy Tab S9 FE
class TabletTest : AppTestBase() {
    @Test fun tablet() {
        launch()
        shot("t-live")
        scenario.onActivity { vm.chooseGroup("cat:1"); vm.play(vm.channelsFor("cat:1"), vm.view.byId[100]!!) }
        idle { vm.page == Page.Player }
        shot("t-player")
    }
}
