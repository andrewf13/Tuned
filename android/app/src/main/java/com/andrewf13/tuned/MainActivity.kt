package com.andrewf13.tuned

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.andrewf13.tuned.player.PlayerController
import com.andrewf13.tuned.player.RefreshRateMatcher
import com.andrewf13.tuned.ui.HomeScreen
import com.andrewf13.tuned.ui.LoadingScreen
import com.andrewf13.tuned.ui.LoginScreen
import com.andrewf13.tuned.ui.BackupScreen
import com.andrewf13.tuned.ui.ExitDialog
import com.andrewf13.tuned.ui.PairDialog
import com.andrewf13.tuned.ui.GroupChannelsScreen
import com.andrewf13.tuned.ui.GroupsScreen
import com.andrewf13.tuned.ui.PlayerOverlay
import com.andrewf13.tuned.ui.ProfilesScreen
import com.andrewf13.tuned.ui.VideoLayer
import com.andrewf13.tuned.ui.SettingsScreen
import com.andrewf13.tuned.ui.TunedTheme

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()
    private lateinit var player: PlayerController
    private lateinit var matcher: RefreshRateMatcher
    lateinit var remote: com.andrewf13.tuned.remote.RemoteController
        private set
    private var stopped = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        player = PlayerController(this)
        matcher = RefreshRateMatcher(this)
        remote = com.andrewf13.tuned.remote.RemoteController(vm, com.andrewf13.tuned.remote.RemoteDiscovery(this))
        com.andrewf13.tuned.ui.Palette.theme = com.andrewf13.tuned.ui.Themes.byId(vm.prefs.themeId)
        setContent { TunedTheme { App() } }
    }

    @Composable
    private fun App() {
        LaunchedEffect(vm.volumeBoostDb) { player.setBoostDb(vm.volumeBoostDb) }
        LaunchedEffect(vm.bufferMode) { player.setBufferMode(vm.bufferMode) }
        val showVideo = vm.playing != null && (vm.screen == Screen.Home || vm.screen == Screen.Player)
        if (exitPrompt) ExitDialog(onExit = ::exitApp, onStay = { exitPrompt = false })
        vm.pairing?.let { (device, code) -> PairDialog(device, code, onCancel = { vm.pairing = null }) }
        Box(Modifier.fillMaxSize()) {
            // Same slot in both screens, so the channel keeps playing between them.
            if (showVideo) VideoLayer(vm, player, matcher)
            when (vm.screen) {
                Screen.Login -> LoginScreen(vm)
                Screen.Loading -> LoadingScreen()
                Screen.Home -> HomeScreen(vm)
                Screen.Player -> PlayerOverlay(vm, player, matcher)
                Screen.Settings -> SettingsScreen(vm, matcher, onExit = ::exitApp, onForgetPhones = remote::forgetPhones)
                Screen.Groups -> GroupsScreen(vm)
                Screen.GroupChannels -> GroupChannelsScreen(vm)
                Screen.Backup -> BackupScreen(vm, onBack = vm::closeBackup)
                Screen.Profiles -> ProfilesScreen(vm)
            }
        }
    }

    /** Hold Back anywhere to get the "Exit Tuned?" prompt (a short press is used for navigation). */
    private var exitPrompt by mutableStateOf(false)
    private var swallowBack = false

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && event.repeatCount >= 2) {
            if (!exitPrompt) exitPrompt = true
            swallowBack = true
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        // Releasing the held Back must not also count as a normal Back press.
        if (keyCode == KeyEvent.KEYCODE_BACK && swallowBack) {
            swallowBack = false
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    fun exitApp() {
        player.stop()
        matcher.reset()
        finishAndRemoveTask()
    }

    override fun onStart() {
        super.onStart()
        remote.start() // phone remote works while Tuned is on screen
        if (stopped && vm.playing != null && (vm.screen == Screen.Player || vm.screen == Screen.Home)) player.resume()
        stopped = false
    }

    override fun onStop() {
        super.onStop()
        remote.stop()
        stopped = true
        player.stop()
        matcher.reset()
    }

    override fun onDestroy() {
        player.release()
        matcher.release()
        super.onDestroy()
    }
}
