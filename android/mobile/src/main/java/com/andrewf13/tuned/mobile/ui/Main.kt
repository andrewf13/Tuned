package com.andrewf13.tuned.mobile.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.andrewf13.tuned.data.Channel
import com.andrewf13.tuned.data.Programme
import com.andrewf13.tuned.mobile.MobileViewModel
import com.andrewf13.tuned.mobile.Tab
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private val tabs = listOf(
    Tab.Live to ("Live TV" to Icons.Filled.Home), Tab.Favourites to ("Favourites" to Icons.Filled.Star),
    Tab.Sports to ("Sports" to Icons.Filled.ThumbUp), Tab.Settings to ("Settings" to Icons.Filled.Settings),
)

/** The app's home: Live TV, Favourites, Sports, Settings. Bottom bar on phones, side rail on tablets. */
@Composable
fun MainScreen(vm: MobileViewModel) {
    val wide = LocalConfiguration.current.screenWidthDp >= 700
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(vm.status) { vm.status?.let { snack.showSnackbar(it); vm.status = null } }
    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        bottomBar = {
            if (!wide) NavigationBar {
                tabs.forEach { (t, l) -> NavigationBarItem(vm.tab == t, { vm.tab = t }, icon = { Icon(l.second, null) }, label = { Text(l.first) }) }
            }
        },
    ) { pad ->
        Row(Modifier.fillMaxSize().padding(pad)) {
            if (wide) NavigationRail {
                tabs.forEach { (t, l) -> NavigationRailItem(vm.tab == t, { vm.tab = t }, icon = { Icon(l.second, null) }, label = { Text(l.first) }) }
            }
            Box(Modifier.weight(1f)) {
                when (vm.tab) {
                    Tab.Live -> LiveTab(vm, wide, snack)
                    Tab.Favourites -> ChannelList(vm, vm.view.favourites(vm.favourites), wide, snack, empty = "No favourites yet.\nLong-press any channel to add it.")
                    Tab.Sports -> SportsTab(vm, wide)
                    Tab.Settings -> SettingsTab(vm)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LiveTab(vm: MobileViewModel, wide: Boolean, snack: SnackbarHostState) {
    var picker by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    val list = remember(vm.view, vm.groupKey, vm.query, vm.favourites) { vm.channelsFor(vm.groupKey) }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                if (searching) {
                    OutlinedTextField(vm.query, { vm.query = it }, singleLine = true, placeholder = { Text("Search all channels") }, modifier = Modifier.fillMaxWidth())
                } else if (!wide) {
                    Row(Modifier.clip(RoundedCornerShape(8.dp)).clickable { picker = true }.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(vm.labelFor(vm.groupKey), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        Text("  ▾", color = MaterialTheme.colorScheme.primary)
                    }
                } else Text(vm.labelFor(vm.groupKey))
            },
            actions = {
                IconButton({ searching = !searching; if (!searching) vm.query = "" }) { Icon(Icons.Filled.Search, "Search") }
            },
        )
        if (vm.query.isBlank()) {
            val recents = vm.recentChannels()
            if (recents.isNotEmpty()) {
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                    items(recents, key = { it.streamId }) { c -> AssistChip({ vm.play(listOf(c) + (list - c), c) }, label = { Text(c.name, maxLines = 1) }) }
                }
            }
        }
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                GroupList(vm, Modifier.width(280.dp).fillMaxHeight()) { vm.chooseGroup(it) }
                ChannelList(vm, list, wide, snack, empty = if (vm.loading) "Loading channels…" else "No channels here")
            }
        } else {
            ChannelList(vm, list, wide, snack, empty = if (vm.loading) "Loading channels…" else if (vm.query.length in 1..1) "Type at least two letters" else "No channels here")
        }
    }
    if (picker) {
        ModalBottomSheet(onDismissRequest = { picker = false }) {
            GroupList(vm, Modifier.fillMaxWidth().height(560.dp)) { vm.chooseGroup(it); picker = false }
        }
    }
}

/** Favourites, All, then the ticked groups, with a filter box for long lists. */
@Composable
private fun GroupList(vm: MobileViewModel, modifier: Modifier, onPick: (String) -> Unit) {
    var filter by remember { mutableStateOf("") }
    val items = remember(vm.view, filter) {
        listOf("fav" to "★ Favourites", "all" to "All channels") +
            vm.view.categories.filter { filter.isBlank() || it.name.contains(filter, true) }.map { "cat:${it.id}" to it.name }
    }
    Column(modifier) {
        OutlinedTextField(filter, { filter = it }, singleLine = true, placeholder = { Text("Find a group") }, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp))
        LazyColumn {
            items(items, key = { it.first }) { (k, label) ->
                val on = k == vm.groupKey
                Text(
                    label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (on) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).clip(RoundedCornerShape(24.dp))
                        .background(if (on) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent)
                        .clickable { onPick(k) }.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
    }
}

@Composable
fun ChannelList(vm: MobileViewModel, list: List<Channel>, wide: Boolean, snack: SnackbarHostState, empty: String) {
    val scope = rememberCoroutineScope()
    if (list.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) { Text(empty, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        return
    }
    val fav: (Channel) -> Unit = { c ->
        vm.toggleFavourite(c.streamId)
        scope.launch { snack.showSnackbar(if (c.streamId in vm.favourites) "Added ${c.name} to Favourites" else "Removed ${c.name} from Favourites") }
    }
    if (wide) {
        LazyVerticalGrid(GridCells.Adaptive(340.dp), contentPadding = PaddingValues(8.dp)) {
            items(list.size, key = { list[it].streamId }) { i -> val c = list[i]; ChannelRow(vm, c, false, { vm.play(list, c) }, { fav(c) }) }
        }
    } else {
        LazyColumn {
            items(list, key = { it.streamId }) { c -> ChannelRow(vm, c, false, { vm.play(list, c) }, { fav(c) }) }
        }
    }
}

/** One channel: logo, name, what's on now with progress. Tap to watch, long-press for Favourites. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChannelRow(vm: MobileViewModel, c: Channel, playing: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    var now by remember(c.streamId) { mutableStateOf<Programme?>(null) }
    LaunchedEffect(c.streamId, vm.guideVersion) {
        delay(200)
        val t = System.currentTimeMillis()
        now = vm.repo.epg(c.streamId).firstOrNull { it.isOn(t) }
    }
    Row(
        Modifier.fillMaxWidth()
            .background(if (playing) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(width = 60.dp, height = 38.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
            if (c.icon != null) AsyncImage(c.icon, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(3.dp))
            else Text("${c.num}", style = MaterialTheme.typography.labelMedium)
        }
        Column(Modifier.padding(start = 14.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(c.name, style = MaterialTheme.typography.titleMedium, fontWeight = if (playing) FontWeight.Bold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
            now?.let { p ->
                val t = System.currentTimeMillis()
                Text("${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(p.startMs))}  ${p.title}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                LinearProgressIndicator(progress = { p.progress(t) }, modifier = Modifier.fillMaxWidth().height(2.dp))
            }
        }
        if (c.streamId in vm.favourites) Text("  ★", color = Star)
        if (playing) Text("  ▶", color = MaterialTheme.colorScheme.primary)
    }
}
