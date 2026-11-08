package com.andrewf13.tuned.remote

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

class MainActivity : ComponentActivity() {
    private val vm: RemoteViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { RemoteTheme { RemoteApp(vm) } }
    }

    override fun onResume() {
        super.onResume()
        if (vm.page == Page.Channels) vm.connect() // pick up changes made on the TV
    }
}

/** Material You: the phone's own wallpaper colours on Android 12+, Tuned blue otherwise. */
@Composable
fun RemoteTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(ctx)
        Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(ctx)
        dark -> darkColorScheme(primary = Color(0xFF7CC4FF), background = Color(0xFF0D0F13), surface = Color(0xFF0D0F13))
        else -> lightColorScheme(primary = Color(0xFF1F6FB2))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
fun RemoteApp(vm: RemoteViewModel) {
    when (vm.page) {
        Page.Find -> FindScreen(vm)
        Page.Pair -> PairScreen(vm)
        Page.Channels -> ChannelsScreen(vm)
    }
}
