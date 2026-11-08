package com.andrewf13.tuned.mobile

import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.andrewf13.tuned.mobile.ui.MainScreen
import com.andrewf13.tuned.mobile.ui.PlayerScreen
import com.andrewf13.tuned.mobile.ui.ProfileFormScreen
import com.andrewf13.tuned.mobile.ui.TunedMobileTheme
import com.andrewf13.tuned.mobile.ui.WelcomeScreen
import com.andrewf13.tuned.player.PlayerController

class MainActivity : ComponentActivity() {
    // An explicit factory bound to this app (the library's shared default caches the first Application it sees).
    private val vm: MobileViewModel by viewModels { androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory(application) }
    lateinit var player: PlayerController
        private set
    /** True while shown as a floating picture-in-picture window. */
    var inPip by mutableStateOf(false)
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        player = PlayerController(applicationContext)
        setContent { TunedMobileTheme(vm.themeMode) { App() } }
    }

    @Composable
    private fun App() {
        LaunchedEffect(vm.boostDb) { player.setBoostDb(vm.boostDb) }
        LaunchedEffect(vm.bufferMode) { player.setBufferMode(vm.bufferMode) }
        LaunchedEffect(vm.page) { updatePipParams() }
        when (vm.page) {
            Page.Welcome -> WelcomeScreen(vm)
            Page.ProfileForm -> ProfileFormScreen(vm)
            Page.Main -> MainScreen(vm)
            Page.Player -> PlayerScreen(vm, player, this, inPip)
        }
    }

    private fun pipParams(): PictureInPictureParams? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        val b = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9))
        // Android 12+: slide out of the app while watching and it keeps playing in a small window.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) b.setAutoEnterEnabled(vm.page == Page.Player).setSeamlessResizeEnabled(true)
        return b.build()
    }

    fun updatePipParams() { pipParams()?.let { runCatching { setPictureInPictureParams(it) } } }

    fun enterPip() { pipParams()?.let { runCatching { enterPictureInPictureMode(it) } } }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (vm.page == Page.Player && Build.VERSION.SDK_INT in Build.VERSION_CODES.O until Build.VERSION_CODES.S) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
    }

    override fun onStop() {
        super.onStop()
        if (!inPip) player.stop() // closing the PiP window also lands here
    }

    override fun onStart() {
        super.onStart()
        if (vm.page == Page.Player) player.resume()
    }

    override fun onDestroy() {
        if (isFinishing) player.release()
        super.onDestroy()
    }
}
