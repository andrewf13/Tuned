package com.andrewf13.tuned.mobile.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

val Live = Color(0xFFE5484D)
val Star = Color(0xFFFFC857)

/** Material You on Android 12+ (your wallpaper's colours), Tuned blue otherwise. System, light or dark. */
@Composable
fun TunedMobileTheme(mode: String, content: @Composable () -> Unit) {
    val dark = when (mode) { "light" -> false; "dark" -> true; else -> isSystemInDarkTheme() }
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(ctx)
        Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(ctx)
        dark -> darkColorScheme(primary = Color(0xFF7CC4FF), background = Color(0xFF0D0F13), surface = Color(0xFF0D0F13))
        else -> lightColorScheme(primary = Color(0xFF1F6FB2))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
