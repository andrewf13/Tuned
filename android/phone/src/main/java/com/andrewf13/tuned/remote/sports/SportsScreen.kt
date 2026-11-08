package com.andrewf13.tuned.remote.sports

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalUriHandler
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
    if (e.isLive(now)) return "Live now"
    val days = ((dayStart(e.startMs) - dayStart(now)) / 86_400_000L).toInt()
    return when (days) {
        0 -> "Today"
        1 -> "Tomorrow"
        else -> SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(Date(e.startMs))
    }
}

private fun time(ms: Long) = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms))

/** The Sports tab: your sports, which channel they're on, tap to watch on the TV. */
@Composable
fun SportsTab(vm: RemoteViewModel) {
    val s = vm.sports
    var setup by remember { mutableStateOf(!s.configured) }
    LaunchedEffect(Unit) { s.load() }
    LaunchedEffect(vm.lineup) { s.load() } // re-match after the TV's list changes
    if (setup) { SportsSetup(s) { setup = false }; return }

    val now by produceState(System.currentTimeMillis()) { while (true) { delay(30_000); value = System.currentTimeMillis() } }
    val list = s.visible(now)
    var picking by remember { mutableStateOf<Broadcast?>(null) }
    val wide = LocalConfiguration.current.screenWidthDp >= 700

    Column(Modifier.fillMaxSize()) {
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { FilterChip(s.filter == "all", { s.filter = "all" }, label = { Text("All") }) }
            item { FilterChip(s.filter == "followed", { s.filter = "followed" }, label = { Text("★ Followed") }) }
            items(s.sports.mapNotNull { Sports.byDb(it) }.sortedBy { Sports.all.indexOf(it) }, key = { it.db }) { d ->
                FilterChip(s.filter == d.db, { s.filter = d.db }, label = { Text("${d.emoji} ${d.label.substringBefore(" (")}") })
            }
            item { FilterChip(false, { setup = true }, label = { Text("⚙ Setup") }) }
        }
        if (s.usingFreeKey) {
            Text(
                "Free test key: only one listing per sport per day. Add a TheSportsDB premium key in Setup for the full guide.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        when {
            s.loading && s.events.isEmpty() -> Centered("Loading the sports guide…")
            s.problem != null && list.isEmpty() -> Centered(s.problem!!)
            list.isEmpty() -> Centered(if (s.filter == "followed") "Nothing on that matches your keywords. Add some in Setup." else "Nothing on in the next week.")
            else -> {
                val sections = list.groupBy { section(it, now) }
                val cols = if (wide) GridCells.Adaptive(400.dp) else GridCells.Fixed(1)
                LazyVerticalGrid(
                    columns = cols, contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    sections.forEach { (title, events) ->
                        item(span = { GridItemSpan(maxLineSpan) }, key = "h:$title") {
                            Text(
                                title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                                color = if (title == "Live now") Live else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 8.dp, start = 4.dp),
                            )
                        }
                        items(events, key = { it.id }) { e -> EventCard(e, now, s, onPick = { picking = it }) }
                    }
                }
            }
        }
    }
    picking?.let { b -> ChannelPicker(vm, b) { picking = null } }
}

@Composable
private fun Centered(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EventCard(e: SportEvent, now: Long, s: SportsController, onPick: (Broadcast) -> Unit) {
    val def = Sports.byDb(e.sport)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(def?.emoji ?: "🏆")
                if (e.isLive(now)) {
                    Text("LIVE", color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                        modifier = Modifier.background(Live, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
                }
                Text(
                    (if (e.isLive(now)) "Started " else "") + time(e.startMs) + "  ·  " + (def?.label?.substringBefore(" (") ?: e.sport),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (e.followed) Text("★", color = Star)
            }
            Text(e.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                e.broadcasts.forEach { b -> BroadcastChip(b, s.resolve(b), onTap = { s.watch(b) }, onLong = { onPick(b) }) }
            }
        }
    }
}

private fun flag(country: String) = when (country) {
    "Australia" -> "🇦🇺"; "New Zealand" -> "🇳🇿"; "United Kingdom" -> "🇬🇧"; "Canada" -> "🇨🇦"; "United States" -> "🇺🇸"
    "Ireland" -> "🇮🇪"; "South Africa" -> "🇿🇦"; "India" -> "🇮🇳"; else -> "📺"
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BroadcastChip(b: Broadcast, r: Resolved?, onTap: () -> Unit, onLong: () -> Unit) {
    val on = r != null
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (on) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f),
        modifier = Modifier.combinedClickable(onClick = { if (on) onTap() else onLong() }, onLongClick = onLong),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp).heightIn(min = 36.dp)) {
            Text("${flag(b.country)} ${b.channel}", style = MaterialTheme.typography.labelLarge, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                color = if (on) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                if (r != null) "▶ ${r.channel.name}" + if (r.picked) "  (your pick)" else "" else "Not in your channels · hold to choose",
                style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (on) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Choose the TV channel for a broadcaster yourself. Remembered on this phone. */
@Composable
private fun ChannelPicker(vm: RemoteViewModel, b: Broadcast, close: () -> Unit) {
    val s = vm.sports
    var q by remember { mutableStateOf(b.channel) }
    val suggestions = s.candidates(b).map { it.channel }
    val results = remember(q, vm.lineup) {
        val t = q.trim()
        (suggestions + if (t.length >= 2) vm.lineup.channels.filter { it.name.contains(t, ignoreCase = true) } else emptyList())
            .distinctBy { it.id }.take(40)
    }
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Channel for ${b.channel}") },
        text = {
            Column(Modifier.heightIn(max = 460.dp)) {
                OutlinedTextField(q, { q = it }, singleLine = true, label = { Text("Search your TV's channels") }, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.padding(top = 8.dp)) {
                    items(results, key = { it.id }) { c: TvChannel ->
                        Text(
                            c.name, style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.fillMaxWidth().combinedClickableCompat { s.pick(b, c); close() }.padding(vertical = 10.dp),
                        )
                        HorizontalDivider()
                    }
                }
                if (results.isEmpty()) Text("No channels found. Only channels in the TV's ticked groups are searchable here.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = close) { Text("Close") } },
        dismissButton = { TextButton(onClick = { s.pick(b, null); close() }) { Text("Automatic") } },
    )
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit) = this.combinedClickable(onClick = onClick)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SportsSetup(s: SportsController, done: () -> Unit) {
    var key by remember { mutableStateOf(s.key) }
    var sports by remember { mutableStateOf(s.sports) }
    var countries by remember { mutableStateOf(s.countries) }
    var keywords by remember { mutableStateOf(s.keywords.joinToString(", ")) }
    val uri = LocalUriHandler.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Sports guide", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Listings come from TheSportsDB. A premium key (about US$9 a month via their Patreon) gives the full week of TV listings; " +
                "the free test key \"123\" shows just one listing per sport per day, enough to try it out.",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(key, { key = it }, singleLine = true, label = { Text("TheSportsDB API key") }, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { key = SportsDb.FREE_KEY }) { Text("Use free test key") }
            TextButton(onClick = { uri.openUri("https://www.thesportsdb.com/pricing") }) { Text("Get a key") }
        }
        Text("Sports", style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Sports.all.forEach { d ->
                FilterChip(d.db in sports, { sports = if (d.db in sports) sports - d.db else sports + d.db }, label = { Text("${d.emoji} ${d.label}") })
            }
        }
        Text("Broadcasters from", style = MaterialTheme.typography.titleMedium)
        Text("Countries whose channels your provider carries, e.g. Fox Sports (Australia), Sky Sport (NZ), TSN (Canada).", style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Sports.countries.forEach { c ->
                FilterChip(c in countries, { countries = if (c in countries) countries - c else countries + c }, label = { Text("${flag(c)} $c") })
            }
        }
        Text("Follow (keywords)", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            keywords, { keywords = it }, label = { Text("Teams, series or events, comma separated") },
            supportingText = { Text("Matching events get a ★ and appear under ★ Followed, e.g. Formula 1, MotoGP, Swans, All Blacks") },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    s.save(key, sports, countries, keywords.split(',').map { it.trim() }.filter { it.isNotEmpty() })
                    done()
                },
                enabled = key.isNotBlank() && sports.isNotEmpty(),
            ) { Text("Save") }
            if (s.configured) TextButton(onClick = done) { Text("Cancel") }
        }
        Text("Data from TheSportsDB.com", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
