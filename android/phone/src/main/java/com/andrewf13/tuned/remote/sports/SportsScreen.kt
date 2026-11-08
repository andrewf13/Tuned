package com.andrewf13.tuned.remote.sports

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.andrewf13.tuned.remote.RemoteViewModel
import com.andrewf13.tuned.remote.TvChannel
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val Live = Color(0xFFE5484D)
private val Star = Color(0xFFFFC857)

private fun dayStart(ms: Long) = Calendar.getInstance().apply {
    timeInMillis = ms; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

private fun section(e: SportEvent, now: Long): String {
    if (e.isOn(now)) return "On now"
    return when (((dayStart(e.startMs) - dayStart(now)) / 86_400_000L).toInt()) {
        0 -> "Later today"
        1 -> "Tomorrow"
        else -> SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(Date(e.startMs))
    }
}

private fun time(ms: Long) = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms))

/** The Sports tab: matches in the TV guide for your sports, which channel, tap to watch on the TV. */
@Composable
fun SportsTab(vm: RemoteViewModel) {
    val s = vm.sports
    var setup by remember { mutableStateOf(false) }
    LaunchedEffect(vm.lineup) { s.load() }
    if (setup) { SportsSetup(s) { setup = false }; return }

    val now by produceState(System.currentTimeMillis()) { while (true) { delay(30_000); value = System.currentTimeMillis() } }
    val list = s.visible(now)
    val wide = LocalConfiguration.current.screenWidthDp >= 700

    Column(Modifier.fillMaxSize()) {
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { FilterChip(s.filter == "all", { s.filter = "all" }, label = { Text("All") }) }
            if (s.keywords.isNotEmpty()) item { FilterChip(s.filter == "followed", { s.filter = "followed" }, label = { Text("★ Followed") }) }
            items(s.catalog.filter { it.id in s.sports }, key = { it.id }) { d ->
                FilterChip(s.filter == d.id, { s.filter = d.id }, label = { Text("${d.emoji} ${d.label}") })
            }
            item { FilterChip(false, { setup = true }, label = { Text("⚙ Sports") }) }
        }
        if (s.offline) {
            Text(
                "The TV is off: this is the guide saved on your phone" +
                    (if (s.guideUpdated > 0) " (from " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(s.guideUpdated)) + ")" else "") +
                    ". Turn on the TV and open Tuned to watch.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.tertiaryContainer).padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        when {
            s.loading && s.events.isEmpty() -> Centered("Loading the sports guide from the TV…")
            s.problem != null && list.isEmpty() -> Centered(s.problem!!)
            list.isEmpty() -> Centered(if (s.filter == "followed") "Nothing on matches your keywords." else "No matches in the TV guide for the next week.")
            else -> {
                val sections = list.groupBy { section(it, now) }
                LazyVerticalGrid(
                    columns = if (wide) GridCells.Adaptive(400.dp) else GridCells.Fixed(1), contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    sections.forEach { (title, evs) ->
                        item(span = { GridItemSpan(maxLineSpan) }, key = "h:$title") {
                            Text(
                                title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                                color = if (title == "On now") Live else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 8.dp, start = 4.dp),
                            )
                        }
                        items(evs, key = { it.id }) { e -> EventCard(e, now, s, canWatch = !s.offline) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Centered(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EventCard(e: SportEvent, now: Long, s: SportsController, canWatch: Boolean) {
    val def = s.label(e.sport)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(def?.emoji ?: "🏆")
                when {
                    e.live -> Badge("LIVE", Live)
                    e.replay -> Badge("REPLAY", MaterialTheme.colorScheme.outline)
                }
                Text(
                    "${time(e.startMs)}–${time(e.endMs)}" + (def?.let { "  ·  ${it.label}" } ?: ""),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
                if (e.followed) Text("★", color = Star)
            }
            Text(e.title.removePrefix("LIVE: ").removePrefix("Live: "), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            e.sub?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                e.channels.forEach { c -> ChannelChip(c, enabled = canWatch) { s.watch(c) } }
            }
        }
    }
}

@Composable
private fun Badge(text: String, colour: Color) {
    Text(
        text, color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
        modifier = Modifier.background(colour, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun ChannelChip(c: TvChannel, enabled: Boolean, onTap: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (enabled) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.clickable(enabled = enabled, onClick = onTap),
    ) {
        Text(
            "▶ ${c.name}" + if (c.fav) "  ★" else "", style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (enabled) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SportsSetup(s: SportsController, done: () -> Unit) {
    var sports by remember { mutableStateOf(s.sports) }
    var keywords by remember { mutableStateOf(s.keywords.joinToString(", ")) }
    var replays by remember { mutableStateOf(s.replays) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Your sports", style = MaterialTheme.typography.headlineSmall)
        Text("Matches are found in the TV guide on your TV (Settings → TV guide on the TV chooses which countries' guides it downloads).", style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            s.catalog.forEach { d ->
                FilterChip(d.id in sports, { sports = if (d.id in sports) sports - d.id else sports + d.id }, label = { Text("${d.emoji} ${d.label}") })
            }
        }
        Text("Follow (optional)", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            keywords, { keywords = it }, label = { Text("Teams or events, comma separated") },
            supportingText = { Text("Found in any sport, marked ★ and listed under ★ Followed, e.g. Swans, All Blacks, Big Bash") },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Show replays & highlights", style = MaterialTheme.typography.titleMedium)
                Text("Off: live coverage and new matches only", style = MaterialTheme.typography.bodySmall)
            }
            Switch(replays, { replays = it })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { s.save(sports, keywords.split(',').map { it.trim() }.filter { it.isNotEmpty() }, replays); done() }, enabled = sports.isNotEmpty() || keywords.isNotBlank()) { Text("Save") }
            TextButton(onClick = done) { Text("Cancel") }
        }
    }
}
