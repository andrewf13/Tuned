package com.andrewf13.tuned.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.andrewf13.tuned.AppViewModel

/**
 * Inside one group: tick the channels you want in the list (OK), favourite with hold OK.
 * Same tools as the Groups screen: filter, order, ticked only, tick/untick all.
 */
@Composable
fun GroupChannelsScreen(vm: AppViewModel) {
    val groupId = vm.editingGroupId ?: return
    BackHandler { vm.closeGroupChannels() }
    var filter by rememberSaveable { mutableStateOf("") }
    var tickedOnly by rememberSaveable { mutableStateOf(false) }

    val name = vm.allCategories.firstOrNull { it.id == groupId }?.name ?: "Group"
    val all = remember(groupId, vm.listVersion) { vm.channelsInGroup(groupId) }
    val shown = all.count { vm.isChannelShown(it.streamId) }
    val visible = remember(all, filter, tickedOnly, vm.hiddenChannels) {
        val q = filter.trim()
        all.filter { c ->
            (q.isEmpty() || c.name.contains(q, ignoreCase = true)) && (!tickedOnly || vm.isChannelShown(c.streamId))
        }
    }
    val gridState = rememberLazyGridState()
    LaunchedEffect(filter, tickedOnly, vm.sortAlpha) { gridState.scrollToItem(0) }
    val filterFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { filterFocus.requestWhenReady() }

    Column(Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 28.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Text(name, style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Text(
                "$shown of ${all.size} channels ticked" + if (visible.size != all.size) "  ·  showing ${visible.size}" else "",
                color = Palette.muted, modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SearchBox(
                query = filter, onQuery = { filter = it }, placeholder = "Filter channels",
                modifier = Modifier.weight(1f), buttonFocus = filterFocus,
            )
            FocusCard(onClick = vm::toggleSort) { Text(if (vm.sortAlpha) "Order: A–Z" else "Order: Provider") }
            FocusCard(onClick = { tickedOnly = !tickedOnly }, selected = tickedOnly) { Text(if (tickedOnly) "Ticked only ✓" else "Ticked only") }
            FocusCard(onClick = { vm.tickAllChannels(groupId) }) { Text("Tick all") }
            FocusCard(onClick = { vm.untickAllChannels(groupId) }) { Text("Untick all") }
        }
        Text(
            "OK ticks or unticks a channel. Hold OK to add or remove it from Favourites. " +
                "Unticked channels stay out of the channel list, but Search still finds them. Order applies to every list.",
            color = Palette.muted, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
        )
        if (visible.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (tickedOnly) "No channels ticked in this group." else "No channels match \"$filter\".", color = Palette.muted)
            }
        } else {
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(3),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(visible, key = { it.streamId }) { c ->
                    val on = vm.isChannelShown(c.streamId)
                    FocusCard(
                        onClick = { vm.toggleChannel(c.streamId, groupId) },
                        onLongClick = { vm.toggleFavourite(c.streamId) },
                        selected = on, modifier = Modifier.fillMaxWidth(),
                    ) {
                        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                            if (on) Text("✓", color = Palette.accent, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                        AsyncImage(model = c.icon, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(width = 40.dp, height = 26.dp))
                        Text(
                            c.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (on) androidx.tv.material3.LocalContentColor.current else mutedColor(),
                        )
                        if (c.streamId in vm.favourites) Text("★", color = Palette.fav)
                    }
                }
            }
        }
    }
}
