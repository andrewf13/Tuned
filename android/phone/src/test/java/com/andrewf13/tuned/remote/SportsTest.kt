package com.andrewf13.tuned.remote

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

internal fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, ComponentActivity>.idleUntil(cond: () -> Boolean) {
    repeat(300) { ShadowLooper.idleMainLooper(); runCatching { waitForIdle() }; if (cond()) return; Thread.sleep(20) }
    error("condition never met")
}

internal fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, ComponentActivity>.screenshot(name: String) {
    waitForIdle()
    val v = activity.window.decorView
    val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
    v.draw(Canvas(bmp))
    File("build/screenshots").apply { mkdirs() }.resolve("$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
class SportsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val tv = FakeTv()
    private val server = MockWebServer().apply { dispatcher = tv }
    @Before fun start() = server.start()
    @After fun stop() = runCatching { server.shutdown() }.let { }

    private fun vm(): RemoteViewModel {
        val app = ApplicationProvider.getApplicationContext<Application>()
        RemoteStore(app).apply {
            tv = SavedTv("Tuned on SHIELD Android TV", "127.0.0.1", server.port, "t0k3n")
            sports = setOf("cricket", "f1", "motogp", "nrl", "rugby"); sportsKeywords = listOf("Brazil"); sportsReplays = false
            sportsCache = null; sportsCatalog = null
        }
        return RemoteViewModel(app).also { v -> compose.idleUntil { v.lineup.channels.isNotEmpty() } }
    }

    @Test fun sportsFromTheTvGuide() {
        val vm = vm()
        compose.runOnUiThread { vm.tab = "sports" }
        compose.setContent { RemoteTheme("light") { RemoteApp(vm) } }
        compose.idleUntil { vm.sports.events.size == 5 }
        val s = vm.sports
        assertEquals("LIVE: WBBL - Heat v Hurricanes", s.events[0].title)
        assertEquals(listOf("AU| FOX CRICKET FHD", "AU| FOX CRICKET HD"), s.events[0].channels.map { it.name })
        assertTrue(s.events.none { it.replay })
        assertEquals("Formula 1", s.label("f1")?.label) // catalog comes from the TV
        compose.screenshot("sports-phone")

        compose.runOnUiThread { s.watch(s.events[1].channels[0]) }
        compose.idleUntil { 104 in tv.plays }

        compose.runOnUiThread { s.filter = "followed" }
        assertEquals(listOf("FIA Formula One World Championship"), s.visible(System.currentTimeMillis()).map { it.title })
        compose.runOnUiThread { s.filter = "all"; s.save(s.sports, s.keywords, true) }
        compose.idleUntil { s.events.size == 6 }
        assertTrue(s.events.any { it.replay })
    }

    @Test fun tvOffShowsTheSavedGuide() {
        val vm = vm()
        compose.runOnUiThread { vm.tab = "sports" }
        compose.setContent { RemoteTheme("dark") { RemoteApp(vm) } }
        compose.idleUntil { vm.sports.events.size == 5 }
        // The TV goes off; the phone reopens later.
        server.shutdown()
        val again = RemoteViewModel(ApplicationProvider.getApplicationContext())
        assertEquals(5, again.sports.events.size) // straight from the phone's copy
        compose.runOnUiThread { again.sports.load() }
        compose.idleUntil { !again.sports.loading }
        assertTrue(again.sports.offline)
        assertEquals(null, again.sports.problem)
    }

    @Test fun guideStillDownloading() {
        tv.guideMissing = true
        val vm = vm()
        compose.runOnUiThread { vm.tab = "sports" }
        compose.setContent { RemoteTheme("light") { RemoteApp(vm) } }
        compose.idleUntil { vm.sports.problem != null }
        assertTrue(vm.sports.problem!!.contains("still downloading"))
        assertFalse(vm.sports.offline)
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-xhdpi")
class SportsTabletTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val server = MockWebServer().apply { dispatcher = FakeTv() }
    @Before fun start() = server.start()
    @After fun stop() = server.shutdown()

    @Test fun tablet() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        RemoteStore(app).apply {
            tv = SavedTv("Tuned on SHIELD Android TV", "127.0.0.1", server.port, "t0k3n")
            sports = setOf("cricket", "f1", "motogp", "nrl", "rugby"); sportsKeywords = listOf("Brazil"); sportsCache = null
        }
        val vm = RemoteViewModel(app)
        compose.runOnUiThread { vm.tab = "sports" }
        compose.setContent { RemoteTheme("dark") { RemoteApp(vm) } }
        compose.idleUntil { vm.sports.events.size == 5 }
        compose.screenshot("sports-tablet")
    }
}
