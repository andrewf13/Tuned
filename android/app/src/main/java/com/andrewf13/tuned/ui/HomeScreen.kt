package com.andrewf13.tuned.ui

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.andrewf13.tuned.AppViewModel
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

/**
 * Channel browser. While something is playing it sits over the live video: the group rail
 * folds away when you're in the channel list (press Left to bring it back) so you can still
 * see the picture. OK on a channel plays it behind the list; OK again goes full screen.
 */
@Composable
fun HomeScreen(vm: AppViewModel) {
    val overVideo = vm.playing != null
    val requested = remember { vm.railRequested.also { vm.railRequested = false } }
    var railOpen by remember { mutableStateOf(!overVideo || requested) }
    var railWanted by remember { mutableStateOf(requested) }
    var lastOverVideo by remember { mutableStateOf(overVideo) }
    LaunchedEffect(overVideo) {
        if (overVideo != lastOverVideo) { railOpen = !overVideo; lastOverVideo = overVideo }
    }
    val railFocus = remember { FocusRequester() }

    // Back from the list returns to the picture. (Hold Back anywhere to exit the app.)
    BackHandler(enabled = overVideo) { vm.goFullscreen() }

    LaunchedEffect(railOpen, railWanted) {
        if (railOpen && railWanted) {
            railFocus.requestWhenReady()
            railWanted = false
        }
    }

    Box(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxHeight()) {
            Column(
                Modifier
                    // Over video the panel is only as wide as its lists, so the picture shows to the right.
                    .then(if (!overVideo) Modifier.weight(1f) else Modifier.width(if (railOpen) 856.dp else 576.dp))
                    .fillMaxHeight()
                    .background(Palette.bg.copy(alpha = if (overVideo) 0.94f else 1f))
                    .padding(start = 48.dp, end = 28.dp, top = 28.dp, bottom = 20.dp),
            ) {
                Header(vm, compact = overVideo)
                Spacer(Modifier.size(18.dp))
                Row(Modifier.fillMaxHeight()) {
                    if (railOpen) {
                        var hadFocus by remember { mutableStateOf(false) }
                        CategoryRail(
                            vm, railFocus,
                            Modifier.width(260.dp).onFocusChanged {
                                if (it.hasFocus) hadFocus = true
                                else if (hadFocus && overVideo) { hadFocus = false; railOpen = false }
                            },
                        )
                        Spacer(Modifier.width(20.dp))
                    }
                    ChannelPane(
                        vm,
                        Modifier
                            .then(if (overVideo) Modifier.width(500.dp) else Modifier.weight(1f))
                            .onPreviewKeyEvent { ev ->
                                val k = ev.nativeKeyEvent
                                if (overVideo && !railOpen && k.action == KeyEvent.ACTION_DOWN && k.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                                    railOpen = true; railWanted = true; true
                                } else false
                            },
                    )
                }
            }
            if (overVideo) {
                Box(
                    Modifier.width(140.dp).fillMaxHeight()
                        .background(Brush.horizontalGradient(listOf(Palette.bg.copy(alpha = 0.94f), Palette.bg.copy(alpha = 0f)))),
                )
            }
        }
        vm.playing?.let { p ->
            Box(
                Modifier.align(Alignment.BottomEnd).padding(32.dp)
                    .clip(RoundedCornerShape(8.dp)).background(Palette.bg.copy(alpha = 0.8f))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text("▶  ${p.name}   ·   OK again for full screen   ·   hold Back to exit", color = Palette.text, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun Header(vm: AppViewModel, compact: Boolean) {
    val clock by produceState(initialValue = timeNow()) {
        while (true) { value = timeNow(); delay(15_000) }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Tuned", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.width(20.dp))
        val note = when {
            vm.status != null -> vm.status
            vm.refreshing -> "Updating channels…"
            else -> null
        }
        if (note != null && !compact) {
            Text(note, color = Palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        } else {
            Spacer(Modifier.weight(1f))
        }
        Text(clock, style = MaterialTheme.typography.titleLarge, color = Palette.muted)
        Spacer(Modifier.width(16.dp))
        FocusCard(onClick = vm::openGroups) { Text("Groups") }
        FocusCard(onClick = vm::openSettings) { Text("Settings") }
    }
}

private fun timeNow(): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())

@Composable
private fun CategoryRail(vm: AppViewModel, selectedFocus: FocusRequester, modifier: Modifier) {
    val items = remember(vm.listVersion, vm.favourites) { vm.railItems() }
    val state = rememberLazyListState()
    LaunchedEffect(Unit) {
        val idx = items.indexOfFirst { it.key == vm.selectedKey }
        if (idx > 3) state.scrollToItem(idx - 3)
    }
    LazyColumn(modifier, state = state, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items(items, key = { it.key }) { item ->
            val selected = item.key == vm.selectedKey
            FocusCard(
                onClick = { vm.selectCategory(item.key) },
                selected = selected,
                // Moving through the rail changes the list straight away, no click needed.
                modifier = Modifier.fillMaxWidth()
                    .then(if (selected) Modifier.focusRequester(selectedFocus) else Modifier)
                    .onFocusChanged { if (it.isFocused) vm.selectCategory(item.key) },
            ) {
                Text(item.label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                item.count?.let { Text("$it", color = mutedColor(), style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable
private fun ChannelPane(vm: AppViewModel, modifier: Modifier) {
    val key = vm.selectedKey
    val list = remember(key, vm.listVersion, vm.favourites, vm.searchQuery) { vm.channelsFor(key) }
    val state = rememberLazyListState()
    val target = vm.focusStreamId
    val playingId = vm.playing?.streamId

    LaunchedEffect(key) { if (target == null) state.scrollToItem(0) }
    LaunchedEffect(target, list) {
        val idx = list.indexOfFirst { it.streamId == target }
        if (idx >= 0) state.scrollToItem((idx - 3).coerceAtLeast(0))
    }

    Column(modifier) {
        if (key == "search") {
            SearchBox(
                query = vm.searchQuery, onQuery = { vm.searchQuery = it },
                placeholder = "Search all channels",
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            )
        }
        when {
            list.isEmpty() -> EmptyState(key, vm)
            else -> LazyColumn(state = state, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(list, key = { it.streamId }) { ch ->
                    val isPlaying = ch.streamId == playingId
                    ChannelRow(
                        vm = vm,
                        channel = ch,
                        isFavourite = ch.streamId in vm.favourites,
                        isPlaying = isPlaying,
                        focusMe = ch.streamId == target,
                        onFocused = { vm.focusStreamId = null },
                        onClick = {
                            if (isPlaying) vm.goFullscreen() else vm.preview(list, key, ch)
                        },
                        onLongClick = { vm.toggleFavourite(ch.streamId) },
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyState(key: String, vm: AppViewModel) {
    val msg = when {
        key == "fav" -> "No favourites yet.\nHold OK on any channel to add it here."
        key == "search" -> if (vm.searchQuery.trim().length < 2) "Type at least two letters." else "No channels match \"${vm.searchQuery}\"."
        vm.channels.isEmpty() && vm.refreshing -> "Loading channels…"
        vm.channels.isEmpty() -> "No channels loaded. Check Settings → Refresh."
        key == "all" -> "All groups are hidden. Press Groups to choose some."
        else -> "Nothing here."
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(msg, color = Palette.muted, style = MaterialTheme.typography.titleMedium)
    }
}
