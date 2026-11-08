package com.andrewf13.tuned.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/** One colour scheme. Focus is the light card a focused item turns into. */
data class ThemeColors(
    val id: String,
    val name: String,
    val bg: Color,
    val panel: Color,
    val panelHi: Color,
    val text: Color,
    val muted: Color,
    val accent: Color,
    val focus: Color,
    val onFocus: Color,
)

object Themes {
    val Grey = ThemeColors(
        "grey", "Graphite grey",
        bg = Color(0xFF111112), panel = Color(0xFF1B1B1D), panelHi = Color(0xFF2B2B2F),
        text = Color(0xFFF2F2F3), muted = Color(0xFF8E8E95), accent = Color(0xFFC4C4CC),
        focus = Color(0xFFF2F2F3), onFocus = Color(0xFF111112),
    )
    val Blue = ThemeColors(
        "blue", "Midnight blue",
        bg = Color(0xFF0D0F13), panel = Color(0xFF161922), panelHi = Color(0xFF222736),
        text = Color(0xFFECEFF4), muted = Color(0xFF8C93A3), accent = Color(0xFF7CC4FF),
        focus = Color(0xFFE8F1FB), onFocus = Color(0xFF0D0F13),
    )
    val Green = ThemeColors(
        "green", "Pitch green",
        bg = Color(0xFF0A110E), panel = Color(0xFF121D18), panelHi = Color(0xFF1B2D25),
        text = Color(0xFFE9F3EE), muted = Color(0xFF86A094), accent = Color(0xFF5BD69A),
        focus = Color(0xFFE2F6EB), onFocus = Color(0xFF0A110E),
    )
    /** The mystery one: a deep violet night sky. */
    val Aurora = ThemeColors(
        "aurora", "Aurora violet",
        bg = Color(0xFF0F0C16), panel = Color(0xFF181424), panelHi = Color(0xFF261F3A),
        text = Color(0xFFF0ECFA), muted = Color(0xFF9A92B3), accent = Color(0xFFB69CFF),
        focus = Color(0xFFEDE6FF), onFocus = Color(0xFF0F0C16),
    )
    val all = listOf(Grey, Blue, Green, Aurora)
    fun byId(id: String?) = all.firstOrNull { it.id == id } ?: Blue
}

/** Current colours. Reading these inside a composable recomposes it when the theme changes. */
object Palette {
    var theme by mutableStateOf(Themes.Blue)
    val bg get() = theme.bg
    val panel get() = theme.panel
    val panelHi get() = theme.panelHi
    val text get() = theme.text
    val muted get() = theme.muted
    val accent get() = theme.accent
    val focus get() = theme.focus
    val onFocus get() = theme.onFocus
    val fav = Color(0xFFFFC857)
    val error = Color(0xFFFF7A7A)
}

@Composable
fun TunedTheme(content: @Composable () -> Unit) {
    val tv = androidx.tv.material3.darkColorScheme(
        primary = Palette.accent,
        background = Palette.bg,
        surface = Palette.panel,
        onSurface = Palette.text,
        onBackground = Palette.text,
    )
    val m3 = androidx.compose.material3.darkColorScheme(
        primary = Palette.accent,
        background = Palette.bg,
        surface = Palette.panel,
        onSurface = Palette.text,
        onBackground = Palette.text,
    )
    androidx.tv.material3.MaterialTheme(colorScheme = tv) {
        androidx.compose.material3.MaterialTheme(colorScheme = m3) {
            CompositionLocalProvider(androidx.tv.material3.LocalContentColor provides Palette.text) {
                Box(Modifier.fillMaxSize().background(Palette.bg)) { content() }
            }
        }
    }
}
