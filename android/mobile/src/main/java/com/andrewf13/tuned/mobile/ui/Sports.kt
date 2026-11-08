package com.andrewf13.tuned.mobile.ui

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.andrewf13.tuned.data.Channel
import com.andrewf13.tuned.data.guide.SportsCatalog
import com.andrewf13.tuned.mobile.MobileViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** One match with every channel showing it. */
data class Match(val title: String, val sub: String?, val sport: String?, val startMs: Long, val endMs: Long, val live: Boolean, val replay: Boolean, val channels: List<Channel>)

/** Matches in the guide for your sports, grouped by programme and time (same rules as the TV). */
suspend fun findMatches(vm: MobileViewModel, sports: Set<String>, replays: Boolean): List<Match> = withContext(Dispatchers.IO) {
    val store = vm.guide ?: return@withContext emptyList()
    val now = System.currentTimeMillis()
    val preferred = vm.view.all.map { it.streamId }.toSet() + vm.favourites
    store.sports(sports, emptyList(), replays, now, now + 7 * 86_400_000L)
        .groupBy { "${it.row.title.lowercase()}|${it.row.sub?.lowercase()}|${it.row.startMs / 600_000}" }
        .values.map { g ->
            val r = g.first().row
            Match(
                r.title, r.sub, g.firstNotNullOfOrNull { it.row.sport }, r.startMs, r.stopMs, g.any { it.row.live }, g.all { it.row.replay },
                g.flatMap { it.streams }.distinct().mapNotNull { vm.view.byId[it] }
                    .sortedWith(compareByDescending<Channel> { it.streamId in preferred }.thenBy { it.name.length }).take(6),
            )
        }.filter { it.channels.isNotEmpty() }.sortedBy { it.startMs }
}

private fun dayStart(ms: Long) = Calendar.getInstance().apply { timeInMillis = ms; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
private fun section(m: Match, now: Long) = if (now in m.startMs until m.endMs) "On now" else when (((dayStart(m.startMs) - dayStart(now)) / 86_400_000L).toInt()) {
    0 -> "Later today"; 1 -> "Tomorrow"; else -> SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(Date(m.startMs))
}
private fun t(ms: Long) = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms))

/** Sports: your sports in the TV guide, which channel, tap to watch. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SportsTab(vm: MobileViewModel, wide: Boolean) {
    var filter by remember { mutableStateOf("all") }
    var setup by remember { mutableStateOf(false) }
    val matches by produceState<List<Match>?>(null, vm.sports, vm.sportsReplays, vm.guideVersion, vm.view) { value = findMatches(vm, vm.sports, vm.sportsReplays) }
    val now = System.currentTimeMillis()
    Column(Modifier.fillMaxSize()) {
        Text("Sports", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 16.dp, top = 16.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { FilterChip(filter == "all", { filter = "all" }, label = { Text("All") }) }
            items(SportsCatalog.all.filter { it.id in vm.sports }, key = { it.id }) { s -> FilterChip(filter == s.id, { filter = s.id }, label = { Text("${s.emoji} ${s.label}") }) }
            item { FilterChip(setup, { setup = !setup }, label = { Text("⚙ Choose") }) }
        }
        if (setup) {
            FlowRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SportsCatalog.all.forEach { s -> FilterChip(s.id in vm.sports, { vm.toggleSport(s.id) }, label = { Text("${s.emoji} ${s.label}") }) }
                FilterChip(vm.sportsReplays, vm::toggleReplays, label = { Text("Show replays & highlights") })
            }
        }
        val list = matches?.filter { it.endMs > now && (filter == "all" || it.sport == filter) }
        when {
            vm.guide?.updatedAt == 0L || vm.guide == null -> Hint(if (vm.guideBusy) "Downloading the TV guide…" else "The TV guide hasn't downloaded yet. Settings → TV guide → Update now.")
            list == null -> Hint("Finding matches…")
            list.isEmpty() -> Hint("No matches in the guide for the next week.")
            else -> LazyVerticalGrid(
                if (wide) GridCells.Adaptive(400.dp) else GridCells.Fixed(1), contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                list.groupBy { section(it, now) }.forEach { (title, ms) ->
                    item(span = { GridItemSpan(maxLineSpan) }, key = "h:$title") {
                        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = if (title == "On now") Live else MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp, start = 4.dp))
                    }
                    items(ms, key = { "${it.title}|${it.sub}|${it.startMs}" }) { m -> MatchCard(vm, m) }
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) = Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
    Text(text, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MatchCard(vm: MobileViewModel, m: Match) {
    val s = SportsCatalog.byId(m.sport ?: "")
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(s?.emoji ?: "🏆")
                if (m.live) Text("LIVE", color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                    modifier = Modifier.background(Live, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
                if (m.replay) Text("REPLAY", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                Text("${t(m.startMs)}–${t(m.endMs)}" + (s?.let { "  ·  ${it.label}" } ?: ""), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(m.title.removePrefix("LIVE: ").removePrefix("Live: "), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            m.sub?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                m.channels.forEach { c ->
                    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.clickable { vm.play(m.channels, c) }) {
                        Text("▶ ${c.name}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                    }
                }
            }
        }
    }
}
