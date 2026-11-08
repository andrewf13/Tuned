package com.andrewf13.tuned.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.andrewf13.tuned.AppViewModel
import com.andrewf13.tuned.data.guide.GuideSources
import java.text.DateFormat
import java.util.Date

/** Where the TV guide comes from, and how much of your list it covers. */
@Composable
fun GuideScreen(vm: AppViewModel) {
    BackHandler { vm.openSettings() }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestWhenReady() }
    val store = vm.repo.guide
    val updated = store?.updatedAt ?: 0L

    Column(Modifier.fillMaxSize().padding(horizontal = 96.dp, vertical = 36.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("TV guide", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Your provider's guide is used first. Free community guides from epgshare01 fill in channels it doesn't cover " +
                "(for example Fox Sports and Sky Sport). They're matched to your channels by name. The guide updates about twice a day.",
            color = Palette.muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(900.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 8.dp)) {
            FocusCard(onClick = { vm.refreshGuide(force = true) }, selected = true, modifier = Modifier.focusRequester(first)) {
                Text(if (vm.guideBusy) "Updating…" else "Update now")
            }
            FocusCard(onClick = vm::toggleGuideFromProvider, selected = vm.guideFromProvider) {
                Text((if (vm.guideFromProvider) "✓ " else "") + "Use my provider's guide")
            }
        }
        Text(
            vm.guideStatus?.let { if (updated > 0 && !vm.guideBusy) "$it  ·  updated " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(updated)) else it }
                ?: if (updated == 0L) "Not downloaded yet. Press Update now."
                else "Guide for ${store?.meta("keptCovered") ?: 0} of ${store?.meta("keptTotal") ?: 0} channels in your list  ·  updated " +
                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(updated)),
            color = Palette.accent,
        )
        store?.meta("sources")?.takeIf { it.isNotBlank() }?.let { Text("Sources: $it", color = Palette.muted, style = MaterialTheme.typography.bodySmall) }
        Text("Community guides", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
        Text("Pick the countries whose channels your provider carries. Each adds a few MB to download.", color = Palette.muted, style = MaterialTheme.typography.bodySmall)
        LazyVerticalGrid(GridCells.Fixed(4), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.width(900.dp)) {
            items(GuideSources.countries, key = { it.code }) { c ->
                val on = c.code in vm.guideCountries
                FocusCard(onClick = { vm.toggleGuideCountry(c.code) }, selected = on, modifier = Modifier.fillMaxWidth()) {
                    Text((if (on) "✓  " else "") + c.name)
                }
            }
        }
        Text("Changes apply at the next update (press Update now).", color = Palette.muted, style = MaterialTheme.typography.bodySmall)
        Text("Community guide data: epgshare01.online", color = Palette.muted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 12.dp))
    }
}
