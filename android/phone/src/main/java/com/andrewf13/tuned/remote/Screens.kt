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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelsScreen(vm: RemoteViewModel) {
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(vm.toast) { vm.toast?.let { snack.showSnackbar(it); vm.toast = null } }
    val now by produceState(System.currentTimeMillis()) { while (true) { delay(30_000); value = System.currentTimeMillis() } }
    val list = remember(vm.lineup, vm.filter, vm.query) { vm.visible() }
    val listState = rememberLazyListState()
    val playingId = vm.state?.playing?.id
    var menu by remember { mutableStateOf(false) }

    // Ask the TV for guide data for whatever is on screen.
    LaunchedEffect(list) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? Int } }
            .distinctUntilChanged()
            .collect { ids -> if (ids.isNotEmpty()) vm.needGuide((ids + list.take(20).map { it.id }).distinct()) }
    }
    LaunchedEffect(vm.filter, vm.query) { listState.scrollToItem(0) }

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
            if (menu) {
                Card(Modifier.fillMaxWidth().padding(12.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Paired with ${vm.tvName}", style = MaterialTheme.typography.titleSmall)
                        vm.state?.profile?.let { Text("Provider profile on the TV: $it", style = MaterialTheme.typography.bodySmall) }
                        TextButton(onClick = { menu = false; vm.forgetTv() }) { Text("Forget this TV") }
                    }
                }
            }
            OutlinedTextField(
                vm.query, { vm.query = it }, singleLine = true, placeholder = { Text("Search channels") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            )
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { FilterChip(vm.filter == "fav", { vm.chooseFilter("fav") }, label = { Text("★ Favourites") }) }
                item { FilterChip(vm.filter == "all", { vm.chooseFilter("all") }, label = { Text("All") }) }
                items(vm.lineup.groups, key = { it.id }) { g ->
                    FilterChip(vm.filter == "g:${g.id}", { vm.chooseFilter("g:${g.id}") }, label = { Text(g.name, maxLines = 1) })
                }
            }
            vm.problem?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
            }
            if (list.isEmpty() && !vm.loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (vm.filter == "fav") "No favourites yet. Hold OK on a channel on the TV to add one." else "No channels",
                        textAlign = TextAlign.Center, modifier = Modifier.padding(32.dp),
                    )
                }
            }
            LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
                items(list, key = { it.id }) { ch ->
                    ChannelItem(ch, vm.guide[ch.id], now, ch.id == playingId) { vm.play(ch) }
                }
            }
        }
    }
}

@Composable
private fun ChannelItem(ch: TvChannel, guide: List<GuideItem>?, now: Long, playing: Boolean, onClick: () -> Unit) {
    val on = guide?.firstOrNull { it.isOn(now) }
    val next = guide?.firstOrNull { it.startMs >= (on?.endMs ?: now) }
    val bg = if (playing) MaterialTheme.colorScheme.primaryContainer else Color0
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
