package com.andrewf13.tuned.remote

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import java.text.DateFormat
import java.util.Date

private fun hm(ms: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms))

// ---- Find a TV ---------------------------------------------------------------------------

@Composable
fun FindScreen(vm: RemoteViewModel) {
    val found by remember { vm.finder.scan() }.collectAsState(initial = emptyList())
    var manual by remember { mutableStateOf("") }
    Scaffold { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(24.dp).imePadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Tuned Remote", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
            Text("Open Tuned on your TV, on the same Wi-Fi as this phone. It'll appear below.", style = MaterialTheme.typography.bodyLarge)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text("Looking for TVs…", style = MaterialTheme.typography.labelLarge)
            }
            found.forEach { tv ->
                Card(Modifier.fillMaxWidth().clickable { vm.startPairing(tv) }) {
                    Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("📺", fontSize = 28.sp)
                        Column(Modifier.padding(start = 14.dp).weight(1f)) {
                            Text(tv.name, style = MaterialTheme.typography.titleMedium)
                            Text("${tv.host}:${tv.port}", style = MaterialTheme.typography.bodySmall)
                        }
                        Text("Pair", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            HorizontalDivider()
            Text("Not showing up? Enter the address from Tuned → Settings → Phone remote:", style = MaterialTheme.typography.bodyMedium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    manual, { manual = it }, singleLine = true, placeholder = { Text("192.168.1.20:8642") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.weight(1f),
                )
                Button(onClick = { vm.startPairingAt(manual) }) { Text("Connect") }
            }
            vm.problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

// ---- Pair ----------------------------------------------------------------------------------

@Composable
fun PairScreen(vm: RemoteViewModel) {
    BackHandler { vm.cancelPairing() }
    var code by remember { mutableStateOf("") }
    Scaffold { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(24.dp).imePadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(40.dp))
            Text("Enter the code on your TV", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Text(vm.pairingWith?.name ?: "", style = MaterialTheme.typography.bodyLarge)
            OutlinedTextField(
                code,
                { v ->
                    code = v.filter { it.isDigit() }.take(4)
                    if (code.length == 4) vm.finishPairing(code) { code = "" }
                },
                singleLine = true,
                textStyle = MaterialTheme.typography.displaySmall.copy(textAlign = TextAlign.Center, letterSpacing = 12.sp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.width(240.dp),
            )
            vm.problem?.let { Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
            TextButton(onClick = vm::cancelPairing) { Text("Cancel") }
        }
    }
}

// ---- Channels and guide --------------------------------------------------------------------

/** Below this width: phone layout (one list). Above: tablet layout (groups pane + channel grid). */
private const val TABLET_DP = 700

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelsScreen(vm: RemoteViewModel) {
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(vm.toast) { vm.toast?.let { snack.showSnackbar(it); vm.toast = null } }
    val now by produceState(System.currentTimeMillis()) { while (true) { delay(30_000); value = System.currentTimeMillis() } }
    val list = remember(vm.lineup, vm.filter, vm.query) { vm.visible() }
    val wide = LocalConfiguration.current.screenWidthDp >= TABLET_DP
    var menu by remember { mutableStateOf(false) }

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(vm.tvName.ifEmpty { "TV" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val p = vm.state?.playing
                        Text(
                            if (p != null) "Now on TV: ${p.name}" else if (vm.loading) "Connecting…" else "Nothing playing",
                            style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    TextButton(onClick = vm::connect) { Text("Refresh") }
                    TextButton(onClick = { menu = !menu }) { Text("⋮", fontSize = 20.sp) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            if (menu) MenuCard(vm) { menu = false }
            vm.problem?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
            PrimaryTabRow(selectedTabIndex = if (vm.tab == "sports") 1 else 0) {
                Tab(vm.tab == "channels", { vm.tab = "channels" }, text = { Text("Channels") })
                Tab(vm.tab == "sports", { vm.tab = "sports" }, text = { Text("Sports") })
            }
            if (vm.tab == "sports") {
                com.andrewf13.tuned.remote.sports.SportsTab(vm)
            } else if (wide) {
                Row(Modifier.fillMaxSize()) {
                    GroupPane(vm, Modifier.width(280.dp).fillMaxHeight())
                    Column(Modifier.weight(1f)) {
                        SearchField(vm)
                        ChannelGrid(vm, list, now)
                    }
                }
            } else {
                SearchField(vm)
                FilterChips(vm)
                ChannelList(vm, list, now)
            }
        }
    }
}

@Composable
private fun MenuCard(vm: RemoteViewModel, close: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(12.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Appearance", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("system" to "System", "light" to "Light", "dark" to "Dark").forEach { (k, label) ->
                    FilterChip(vm.themeMode == k, { vm.chooseTheme(k) }, label = { Text(label) })
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Text("Paired with ${vm.tvName}", style = MaterialTheme.typography.titleSmall)
            vm.state?.profile?.let { Text("Provider profile on the TV: $it", style = MaterialTheme.typography.bodySmall) }
            Text("Tip: add the Tuned channels widget to your home screen to change channel without opening the app.", style = MaterialTheme.typography.bodySmall)
            Row {
                TextButton(onClick = close) { Text("Close") }
                TextButton(onClick = { close(); vm.forgetTv() }) { Text("Forget this TV") }
            }
        }
    }
}

@Composable
private fun SearchField(vm: RemoteViewModel) {
    OutlinedTextField(
        vm.query, { vm.query = it }, singleLine = true, placeholder = { Text("Search channels") },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

private data class Filter(val key: String, val label: String)

private fun filters(vm: RemoteViewModel) =
    listOf(Filter("fav", "★ Favourites"), Filter("all", "All")) + vm.lineup.groups.map { Filter("g:${it.id}", it.name) }

@Composable
private fun FilterChips(vm: RemoteViewModel) {
    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(filters(vm), key = { it.key }) { f ->
            FilterChip(vm.filter == f.key, { vm.chooseFilter(f.key) }, label = { Text(f.label, maxLines = 1) })
        }
    }
}

/** Tablet: the TV's groups down the side. */
@Composable
private fun GroupPane(vm: RemoteViewModel, modifier: Modifier) {
    LazyColumn(modifier.background(MaterialTheme.colorScheme.surfaceContainerLow), contentPadding = PaddingValues(8.dp)) {
        items(filters(vm), key = { it.key }) { f ->
            val on = vm.filter == f.key && vm.query.isBlank()
            Text(
                f.label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall,
                color = if (on) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
                    .background(if (on) MaterialTheme.colorScheme.secondaryContainer else Color0)
                    .clickable { vm.query = ""; vm.chooseFilter(f.key) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun EmptyList(vm: RemoteViewModel) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            if (vm.filter == "fav") "No favourites yet. Hold OK on a channel on the TV to add one." else "No channels",
            textAlign = TextAlign.Center, modifier = Modifier.padding(32.dp),
        )
    }
}

@Composable
private fun ChannelList(vm: RemoteViewModel, list: List<TvChannel>, now: Long) {
    val state = rememberLazyListState()
    LaunchedEffect(list) {
        snapshotFlow { state.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? Int } }
            .distinctUntilChanged()
            .collect { ids -> if (ids.isNotEmpty()) vm.needGuide((ids + list.take(20).map { it.id }).distinct()) }
    }
    LaunchedEffect(vm.filter, vm.query) { state.scrollToItem(0) }
    if (list.isEmpty() && !vm.loading) { EmptyList(vm); return }
    val playingId = vm.state?.playing?.id
    LazyColumn(state = state, contentPadding = PaddingValues(bottom = 24.dp)) {
        items(list, key = { it.id }) { ch ->
            ChannelItem(ch, vm.guide[ch.id], now, ch.id == playingId) { vm.play(ch) }
        }
    }
}

/** Tablet: channels as cards in a grid. */
@Composable
private fun ChannelGrid(vm: RemoteViewModel, list: List<TvChannel>, now: Long) {
    val state = rememberLazyGridState()
    LaunchedEffect(list) {
        snapshotFlow { state.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? Int } }
            .distinctUntilChanged()
            .collect { ids -> if (ids.isNotEmpty()) vm.needGuide((ids + list.take(30).map { it.id }).distinct()) }
    }
    LaunchedEffect(vm.filter, vm.query) { state.scrollToItem(0) }
    if (list.isEmpty() && !vm.loading) { EmptyList(vm); return }
    val playingId = vm.state?.playing?.id
    LazyVerticalGrid(
        columns = GridCells.Adaptive(330.dp), state = state,
        contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(list, key = { it.id }) { ch ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (ch.id == playingId) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                ),
            ) {
                ChannelItem(ch, vm.guide[ch.id], now, ch.id == playingId, highlight = false) { vm.play(ch) }
            }
        }
    }
}

@Composable
private fun ChannelItem(ch: TvChannel, guide: List<GuideItem>?, now: Long, playing: Boolean, highlight: Boolean = true, onClick: () -> Unit) {
    val on = guide?.firstOrNull { it.isOn(now) }
    val next = guide?.firstOrNull { it.startMs >= (on?.endMs ?: now) }
    val bg = if (playing && highlight) MaterialTheme.colorScheme.primaryContainer else Color0
    Row(
        Modifier.fillMaxWidth().background(bg).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(width = 64.dp, height = 40.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
            if (ch.icon != null) AsyncImage(ch.icon, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(4.dp))
            else Text("${ch.num}", style = MaterialTheme.typography.labelMedium)
        }
        Column(Modifier.padding(start = 14.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(ch.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (ch.fav) Text("  ★", color = androidx.compose.ui.graphics.Color(0xFFFFC857))
                if (playing) Text("  ▶ on TV", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            if (on != null) {
                Text("${hm(on.startMs)}–${hm(on.endMs)}  ${on.title}", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                LinearProgressIndicator(progress = { on.progress(now) }, modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)))
            } else if (guide != null) {
                Text("No guide information", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            next?.let { Text("Next ${hm(it.startMs)}  ${it.title}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

private val Color0 = androidx.compose.ui.graphics.Color.Transparent
