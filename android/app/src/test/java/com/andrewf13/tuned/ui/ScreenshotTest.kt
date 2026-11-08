package com.andrewf13.tuned.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import android.app.Application
import com.andrewf13.tuned.AppViewModel
import com.andrewf13.tuned.data.Account
import com.andrewf13.tuned.data.Prefs
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import com.andrewf13.tuned.player.PlayerController
import com.andrewf13.tuned.player.RefreshRateMatcher
import androidx.compose.runtime.Composable
import com.andrewf13.tuned.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders real screens with a fake saved channel list so the layout can be eyeballed
 * without a TV. Output: app/build/screenshots/. Run: ./gradlew testDebugUnitTest --tests '*ScreenshotTest*'
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi")
class ScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun seededVm(): AppViewModel {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val cats = listOf("AU | Sport", "NZ | Sport", "AU | Free to air", "UK | Sport", "US | NFL Game Pass", "F1 / Motorsport", "Cricket")
        File(app.filesDir, "live_categories.json").writeText(
            cats.mapIndexed { i, n -> """{"category_id":"${i + 1}","category_name":"$n","parent_id":0}""" }.joinToString(",", "[", "]"),
        )
        val names = listOf(
            "Fox Footy HD" to 1, "Fox League HD" to 1, "Fox Cricket HD" to 7, "Kayo Sports 1" to 1, "Sky Sport 1 NZ" to 2,
            "Sky Sport 2 NZ" to 2, "ABC TV HD" to 3, "Seven HD" to 3, "Nine HD" to 3, "SBS HD" to 3, "Sky Sports F1 UHD" to 6,
            "NFL Network" to 5, "Sky Sports Main Event" to 4, "WRC+ Live" to 6, "TNT Sports 1" to 4,
        )
        File(app.filesDir, "live_streams.json").writeText(
            names.mapIndexed { i, (n, c) -> """{"num":${i + 1},"name":"$n","stream_id":${100 + i},"category_id":"$c","stream_icon":""}""" }
                .joinToString(",", "[", "]"),
        )
        Prefs(app).apply {
            account = Account("http://127.0.0.1:9", "demo", "demo")
            favourites = setOf(100, 101, 104)
            resumeOnStart = false
            lastStreamId = 102
            recents = listOf(102, 100, 104, 110, 106)
        }
        return AppViewModel(app)
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun home() {
        val vm = seededVm()
        compose.setContent { TunedTheme { HomeScreen(vm) } }
        compose.waitUntil(5_000) { vm.channels.isNotEmpty() }
        compose.mainClock.advanceTimeBy(2_000)
        shot("home")
    }

    @Test fun favourites() {
        val vm = seededVm()
        vm.selectCategory("fav")
        compose.setContent { TunedTheme { HomeScreen(vm) } }
        compose.waitUntil(5_000) { vm.channels.isNotEmpty() }
        shot("favourites")
    }

    @Test fun login() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        Prefs(app).account = null
        val vm = AppViewModel(app)
        compose.setContent { TunedTheme { LoginScreen(vm) } }
        shot("login")
    }

    private fun waitLoaded(vm: AppViewModel) {
        repeat(200) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            if (vm.channels.isNotEmpty()) return
            Thread.sleep(25)
        }
        error("channels never loaded")
    }

    /** Stand-in for live video: a bright block, so the scrim and see-through areas are visible. */
    private val fakeVideo = Modifier.fillMaxSize().background(Color(0xFF2E7D32))

    @Test fun homeOverVideo() {
        val vm = seededVm()
        compose.setContent { TunedTheme { Box(fakeVideo) { HomeScreen(vm) } } }
        compose.waitUntil(5_000) { vm.channels.isNotEmpty() }
        compose.runOnUiThread { vm.selectCategory("cat:1"); vm.preview(vm.channelsFor("cat:1"), "cat:1", vm.channels[1]) }
        compose.waitForIdle()
        shot("home-over-video")
    }

    @Test fun groups() {
        val vm = seededVm()
        waitLoaded(vm)
        compose.runOnUiThread { vm.hideAllGroups(); vm.toggleGroup("1"); vm.toggleGroup("2"); vm.toggleGroup("6") }
        compose.setContent { TunedTheme { GroupsScreen(vm) } }
        shot("groups")
    }

    @Test fun settings() {
        val vm = seededVm()
        waitLoaded(vm)
        val matcher = RefreshRateMatcher(compose.activity)
        compose.setContent { TunedTheme { SettingsScreen(vm, matcher) } }
        shot("settings")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun playerPanels() {
        val vm = seededVm()
        waitLoaded(vm)
        val player = PlayerController(compose.activity)
        val matcher = RefreshRateMatcher(compose.activity)
        compose.runOnUiThread { vm.selectCategory("cat:1"); vm.play(vm.channelsFor("cat:1"), "cat:1", vm.channels[2]) }
        compose.setContent { TunedTheme { Box(fakeVideo) { PlayerOverlay(vm, player, matcher) } } }
        compose.waitForIdle()
        shot("player-info")
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
        shot("player-recents")
        compose.runOnUiThread { player.release() }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun playerChannelList() {
        val vm = seededVm()
        waitLoaded(vm)
        val player = PlayerController(compose.activity)
        val matcher = RefreshRateMatcher(compose.activity)
        compose.runOnUiThread { vm.selectCategory("all"); vm.play(vm.channelsFor("all"), "all", vm.channels[2]) }
        compose.setContent { TunedTheme { Box(fakeVideo) { PlayerOverlay(vm, player, matcher) } } }
        compose.waitForIdle()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionLeft) }
        compose.waitForIdle()
        shot("player-list")
        compose.runOnUiThread { player.release() }
    }

    @Composable
    private fun App(vm: AppViewModel, player: PlayerController, matcher: RefreshRateMatcher) {
        Box(fakeVideo) {
            when (vm.screen) {
                Screen.Home -> HomeScreen(vm)
                Screen.Player -> PlayerOverlay(vm, player, matcher)
                Screen.Groups -> GroupsScreen(vm)
                else -> {}
            }
        }
    }

    @OptIn(ExperimentalTestApi::class)
    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    /** The reported bug: search → watch → back to list must not trap you in the search box. */
    @Test fun searchDoesNotTrap() {
        val vm = seededVm()
        waitLoaded(vm)
        val player = PlayerController(compose.activity)
        val matcher = RefreshRateMatcher(compose.activity)
        compose.runOnUiThread {
            vm.selectCategory("search"); vm.searchQuery = "fox"
            val results = vm.channelsFor("search")
            vm.play(results, "search", results[1])
        }
        compose.setContent { TunedTheme { App(vm, player, matcher) } }
        compose.waitForIdle()
        compose.runOnUiThread { vm.closePlayer() } // Back from full screen
        compose.waitForIdle()
        shot("search-after-back")
        press(Key.DirectionLeft) // opens the groups column
        press(Key.DirectionDown) // Search → Favourites
        shot("search-escaped")
        assertEquals("fav", vm.selectedKey)
        compose.runOnUiThread { player.release() }
    }

    @Test fun searchIsNotRememberedAcrossRestarts() {
        val vm = seededVm()
        waitLoaded(vm)
        compose.runOnUiThread { vm.selectCategory("cat:2"); vm.selectCategory("search") }
        val again = AppViewModel(ApplicationProvider.getApplicationContext())
        assertEquals("cat:2", again.selectedKey)
    }

    /** Left opens the group's channels; Left again shows the groups. */
    @Test fun secondLeftOpensGroups() {
        val vm = seededVm()
        waitLoaded(vm)
        val player = PlayerController(compose.activity)
        val matcher = RefreshRateMatcher(compose.activity)
        compose.runOnUiThread { vm.selectCategory("all"); vm.play(vm.channelsFor("cat:2"), "cat:2", vm.channelsFor("cat:2")[0]) }
        compose.setContent { TunedTheme { App(vm, player, matcher) } }
        compose.waitForIdle()
        press(Key.DirectionLeft)
        press(Key.DirectionLeft)
        assertEquals(Screen.Home, vm.screen)
        assertEquals("cat:2", vm.selectedKey)
        shot("groups-from-player")
        press(Key.DirectionDown) // focus is in the rail: moving selects the next group
        assertTrue(vm.selectedKey != "cat:2")
        compose.runOnUiThread { player.release() }
    }

    @Test fun groupsFilteredAndSorted() {
        val vm = seededVm()
        waitLoaded(vm)
        compose.runOnUiThread { vm.toggleGroupSort(); vm.hideAllGroups(); vm.toggleGroup("7"); vm.toggleGroup("1") }
        assertEquals(listOf("AU | Free to air", "AU | Sport", "Cricket"), vm.allCategories.take(3).map { it.name })
        compose.setContent { TunedTheme { GroupsScreen(vm) } }
        shot("groups-az")
        assertEquals(listOf("AU | Sport", "Cricket"), vm.categories.map { it.name })
    }

    @Test fun themes() {
        val vm = seededVm()
        waitLoaded(vm)
        compose.setContent { TunedTheme { HomeScreen(vm) } }
        for (t in Themes.all) {
            compose.runOnUiThread { Palette.theme = t }
            compose.waitForIdle()
            shot("theme-${t.id}")
        }
        compose.runOnUiThread { Palette.theme = Themes.Blue }
    }

    @Test fun backupScreen() {
        val vm = seededVm()
        waitLoaded(vm)
        compose.setContent { TunedTheme { BackupScreen(vm, onBack = {}) } }
        compose.waitForIdle()
        shot("backup")
    }

    /** Save → wipe → restore brings back login, favourites, groups and settings. */
    @Test fun backupRoundTrip() {
        val vm = seededVm()
        waitLoaded(vm)
        compose.runOnUiThread { vm.hideAllGroups(); vm.toggleGroup("2"); vm.toggleSort(); vm.cycleTheme() }
        val app = ApplicationProvider.getApplicationContext<Application>()
        val prefs = Prefs(app)
        val enc = com.andrewf13.tuned.data.BackupCrypto.encrypt(
            com.andrewf13.tuned.data.Backup.export(prefs), "pass-1234".toCharArray(),
        )
        app.getSharedPreferences("tuned", 0).edit().clear().commit()
        assertEquals(null, Prefs(app).account)
        val plain = com.andrewf13.tuned.data.BackupCrypto.decrypt(enc, "pass-1234".toCharArray())
        com.andrewf13.tuned.data.Backup.import(plain, Prefs(app))
        val back = Prefs(app)
        assertEquals("demo", back.account?.username)
        assertEquals(setOf(100, 101, 104), back.favourites)
        assertEquals(setOf("2"), back.visibleGroups)
        assertEquals(true, back.sortAlpha)
        assertEquals("green", back.themeId)
        compose.runOnUiThread { Palette.theme = Themes.Blue }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun channelsInsideAGroup() {
        val vm = seededVm()
        waitLoaded(vm)
        // Untick one channel: it leaves the group's list and All channels, Search still finds it.
        compose.runOnUiThread { vm.toggleChannel(101, "1") }
        assertEquals(listOf(100, 103), vm.channelsFor("cat:1").map { it.streamId })
        assertTrue(vm.channelsFor("all").none { it.streamId == 101 })
        compose.runOnUiThread { vm.searchQuery = "league" }
        assertEquals(listOf(101), vm.channelsFor("search").map { it.streamId })
        // Untick every channel: the group drops out of the rail.
        compose.runOnUiThread { vm.untickAllChannels("1") }
        assertTrue(vm.railItems().none { it.key == "cat:1" })
        // Hide the UK group, then tick one of its channels: the group comes back with just that one.
        compose.runOnUiThread { vm.hideAllGroups(); vm.untickAllChannels("4"); vm.toggleChannel(112, "4") }
        assertEquals(listOf(112), vm.channelsFor("cat:4").map { it.streamId })
        assertTrue(vm.isGroupShown("4"))

        compose.runOnUiThread { vm.tickAllChannels("1"); vm.toggleChannel(102, "7"); vm.openGroupChannels("1") }
        compose.setContent { TunedTheme { GroupChannelsScreen(vm) } }
        compose.waitForIdle()
        compose.runOnUiThread { vm.toggleChannel(101, "1") }
        compose.waitForIdle()
        shot("group-channels")
        // Hold OK on a channel = favourite. Focus starts on the filter; move down to the first channel.
        compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        compose.waitForIdle()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter, 1_500) }
        compose.waitForIdle()
        assertTrue("hold OK should un-favourite channel 100", 100 !in vm.favourites)
    }

    @Test fun groupsWithChannelButton() {
        val vm = seededVm()
        waitLoaded(vm)
        compose.runOnUiThread { vm.toggleChannel(101, "1") }
        compose.setContent { TunedTheme { GroupsScreen(vm) } }
        shot("groups-with-channels")
    }
}
