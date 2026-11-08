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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.andrewf13.tuned.AppViewModel

/**
 * Pick which provider groups appear in the channel list. With hundreds of groups:
 * filter by text (e.g. "AU", "NZ", "sport"), sort A–Z, or show only the ticked ones.
 * Hidden groups are still searchable.
 */
@Composable
fun GroupsScreen(vm: AppViewModel) {
    BackHandler { vm.backToHome() }
    var filter by rememberSaveable { mutableStateOf("") }
    var tickedOnly by rememberSaveable { mutableStateOf(false) }

    val all = vm.allCategories
    val shown = all.count { vm.isGroupShown(it.id) }
    val visible = remember(all, filter, tickedOnly, vm.groupFilter) {
        val q = filter.trim()
        all.filter { g ->
            (q.isEmpty() || g.name.contains(q, ignoreCase = true)) && (!tickedOnly || vm.isGroupShown(g.id))
        }
    }
    val gridState = rememberLazyGridState()
    LaunchedEffect(filter, tickedOnly, vm.groupSortAlpha) { gridState.scrollToItem(0) }

    val filterFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { filterFocus.requestWhenReady() }

    Column(Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 28.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Text("Groups", style = MaterialTheme.typography.headlineMedium)
            Text(
                "$shown of ${all.size} ticked" + if (visible.size != all.size) "  ·  showing ${visible.size}" else "",
                color = Palette.muted, modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SearchBox(
                query = filter, onQuery = { filter = it }, placeholder = "Filter groups",
                modifier = Modifier.weight(1f), buttonFocus = filterFocus,
            )
            FocusCard(onClick = vm::toggleGroupSort) { Text(if (vm.groupSortAlpha) "Order: A–Z" else "Order: Provider") }
            FocusCard(onClick = { tickedOnly = !tickedOnly }, selected = tickedOnly) { Text(if (tickedOnly) "Ticked only ✓" else "Ticked only") }
            FocusCard(onClick = vm::showAllGroups) { Text("Tick all") }
            FocusCard(onClick = vm::hideAllGroups) { Text("Untick all") }
        }
        Text(
            "OK ticks or unticks a group. Press › (Right, then OK) to choose individual channels inside a group. " +
                "Unticked groups and channels stay out of the channel list, but Search still finds them. " +
                "If you untick all, new groups from your provider stay hidden too. A–Z also orders the group column.",
            color = Palette.muted, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
        )
        if (visible.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (tickedOnly) "No groups ticked yet." else "No groups match \"$filter\".", color = Palette.muted)
            }
        } else {
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(3),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(visible, key = { it.id }) { g ->
                    val on = vm.isGroupShown(g.id)
                    val total = vm.groupCounts[g.id] ?: 0
                    val shownCount = vm.groupShownCounts[g.id] ?: total
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        FocusCard(onClick = { vm.toggleGroup(g.id) }, selected = on, modifier = Modifier.weight(1f)) {
                            Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                                if (on) Text("✓", color = Palette.accent, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            }
                            Text(
                                g.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (on) androidx.tv.material3.LocalContentColor.current else mutedColor(),
                            )
                            Text(
                                if (shownCount == total) "$total" else "$shownCount/$total",
                                color = mutedColor(), style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        // Into the group: pick individual channels.
                        FocusCard(onClick = { vm.openGroupChannels(g.id) }) { Text("›", style = MaterialTheme.typography.titleMedium) }
                    }
                }
            }
        }
    }
}
