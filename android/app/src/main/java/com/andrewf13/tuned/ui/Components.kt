package com.andrewf13.tuned.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text

/** The one focusable building block: quiet at rest, inverted (light card) when focused. */
@Composable
fun FocusCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    selected: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier,
        interactionSource = interaction,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Palette.panelHi else Color.Transparent,
            contentColor = Palette.text,
            focusedContainerColor = Palette.focus,
            focusedContentColor = Palette.onFocus,
            pressedContainerColor = Palette.accent,
            pressedContentColor = Palette.bg,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f),
    ) {
        CompositionLocalProvider(LocalCardFocused provides focused) {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
        }
    }
}

/** True inside a [FocusCard] that has focus. */
val LocalCardFocused = compositionLocalOf { false }

/**
 * One line of text: cut off with "…" at rest, scrolling sideways when its card is focused,
 * so long channel names can be read in full.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MarqueeText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyLarge,
    fontWeight: androidx.compose.ui.text.font.FontWeight? = null,
) {
    val focused = LocalCardFocused.current
    Text(
        text, color = color, style = style, fontWeight = fontWeight, maxLines = 1,
        overflow = if (focused) TextOverflow.Clip else TextOverflow.Ellipsis,
        modifier = if (focused) modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 700, velocity = 40.dp) else modifier,
    )
}

/** Secondary text that stays readable on both the dark rest state and the light focus state. */
@Composable
fun mutedColor() = LocalContentColor.current.copy(alpha = 0.62f)

@Composable
fun LoadingScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Tuned", style = MaterialTheme.typography.displaySmall)
            Text("Loading your channels…", color = Palette.muted, modifier = Modifier.padding(top = 8.dp))
        }
    }
}
