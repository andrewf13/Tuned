package com.andrewf13.tuned.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.andrewf13.tuned.AppViewModel
import com.andrewf13.tuned.data.Channel
import com.andrewf13.tuned.data.Programme
import kotlinx.coroutines.delay

/** One channel in a list. [focusMe] grabs focus once it's composed (then calls [onFocused]). */
@Composable
fun ChannelRow(
    vm: AppViewModel,
    channel: Channel,
    isFavourite: Boolean,
    isPlaying: Boolean,
    focusMe: Boolean,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var now by remember(channel.streamId) { mutableStateOf<Programme?>(null) }
    LaunchedEffect(channel.streamId) {
        delay(250) // don't fetch guide data for rows flying past
        val t = System.currentTimeMillis()
        now = vm.repo.epg(channel.streamId).firstOrNull { it.isOn(t) }
    }
    val fr = remember { FocusRequester() }
    if (focusMe) {
        LaunchedEffect(Unit) {
            withFrameNanos { }
            runCatching { fr.requestFocus() }
            onFocused()
        }
    }
    FocusCard(
        onClick = onClick,
        onLongClick = onLongClick,
        selected = isPlaying,
        modifier = modifier.fillMaxWidth().focusRequester(fr),
    ) {
        Text("${channel.num}", color = mutedColor(), modifier = Modifier.width(52.dp), style = MaterialTheme.typography.bodyMedium)
        AsyncImage(
            model = channel.icon, contentDescription = null, contentScale = ContentScale.Fit,
            modifier = Modifier.size(width = 64.dp, height = 40.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(channel.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
            now?.let {
                Text(it.title, maxLines = 1, overflow = TextOverflow.Ellipsis, color = mutedColor(), style = MaterialTheme.typography.bodySmall)
            }
        }
        if (isPlaying) Text("▶", style = MaterialTheme.typography.titleMedium)
        if (isFavourite) Text("★", color = Palette.fav, style = MaterialTheme.typography.titleMedium)
    }
}
