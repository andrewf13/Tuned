package com.andrewf13.tuned.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.andrewf13.tuned.AppViewModel

/** Every provider login on this device. OK switches; Edit changes or deletes one. */
@Composable
fun ProfilesScreen(vm: AppViewModel) {
    BackHandler { vm.openSettings() }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestWhenReady() }
    Column(Modifier.fillMaxSize().padding(horizontal = 96.dp, vertical = 40.dp)) {
        Text("Profiles", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Each profile is one provider: Xtream Codes or an M3U playlist. Favourites, groups and recent channels are kept separately for each.",
            color = Palette.muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp, bottom = 18.dp).width(900.dp),
        )
        LazyColumn(Modifier.width(900.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(vm.profiles, key = { it.id }) { p ->
                val active = p.id == vm.activeProfileId
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FocusCard(
                        onClick = { if (!active) vm.switchProfile(p.id) else vm.backToHome() },
                        selected = active,
                        modifier = Modifier.weight(1f).then(if (p == vm.profiles.first()) Modifier.focusRequester(first) else Modifier),
                    ) {
                        Column(Modifier.weight(1f)) {
                            MarqueeText(p.name, style = MaterialTheme.typography.titleMedium)
                            MarqueeText(p.summary, color = mutedColor(), style = MaterialTheme.typography.bodySmall)
                        }
                        Text(if (active) "In use" else "Switch", style = MaterialTheme.typography.titleSmall)
                    }
                    FocusCard(onClick = { vm.editProfile(p) }) { Text("Edit") }
                }
            }
            item {
                FocusCard(onClick = vm::addProfile, modifier = Modifier.padding(top = 12.dp)) { Text("+  Add a provider (Xtream Codes or M3U)") }
            }
        }
    }
}
