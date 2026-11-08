package com.andrewf13.tuned.ui

import android.view.KeyEvent
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.andrewf13.tuned.AppViewModel
import com.andrewf13.tuned.data.Channel
import com.andrewf13.tuned.data.Programme
import com.andrewf13.tuned.player.PlayerController
import com.andrewf13.tuned.player.RefreshRateMatcher
import com.andrewf13.tuned.player.StreamInfo
import com.andrewf13.tuned.player.StreamStats
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

/**
 * The picture. Stays alive while you move between full screen and the channel list,
 * so the channel keeps playing. Owns tuning, refresh-rate matching and "recently watched".
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun VideoLayer(vm: AppViewModel, player: PlayerController, matcher: RefreshRateMatcher) {
    val channel = vm.playing ?: return
    val info by player.info.collectAsState()
    val error by player.error.collectAsState()
    val buffering by player.buffering.collectAsState()

    val url = vm.streamUrl(channel)
    LaunchedEffect(url) {
        delay(300) // lets fast zapping skip channels without tuning each one
        player.play(url)
        delay(5_000)
        vm.markWatched(channel.streamId)
    }
    // Refresh-rate match once the frame rate is known; waits a moment so zapping doesn't flicker the TV.
    LaunchedEffect(channel.streamId, info.fps, vm.afrEnabled) {
        if (vm.afrEnabled && info.fps > 0f) { delay(1_200); matcher.match(info.fps) }
    }
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false; player.stop(); matcher.reset() }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    setKeepContentOnPlayerReset(true)
                    isFocusable = false
                    descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    this.player = player.exo
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (error != null) {
            Box(Modifier.align(Alignment.Center).clip(RoundedCornerShape(12.dp)).background(Color(0xCC000000)).padding(24.dp)) {
                Text(error!!, color = Palette.text, style = MaterialTheme.typography.titleMedium)
            }
        } else if (buffering) {
            Text("Loading…", color = Palette.muted, modifier = Modifier.align(Alignment.Center))
        }
    }
}

private enum class Panel { None, Info, Recents, List }

/**
 * Full-screen controls on top of [VideoLayer].
 * Up/Down: change channel · Left: this group's channels · Right: previous channel ·
 * OK: info + recent channels · hold OK/Menu: favourite · Back: channel list (keeps playing).
 * Left twice: the full channel list with groups.
 */
@Composable
fun PlayerOverlay(vm: AppViewModel, player: PlayerController, matcher: RefreshRateMatcher) {
    val channel = vm.playing ?: return
    val info by player.info.collectAsState()

    var panel by remember { mutableStateOf(Panel.Info) }
    var nonce by remember { mutableIntStateOf(0) }
    fun flashInfo() { panel = Panel.Info; nonce++ }

    LaunchedEffect(channel.streamId) { if (panel == Panel.None || panel == Panel.Info) flashInfo() }
    LaunchedEffect(panel, nonce) { if (panel == Panel.Info) { delay(5_000); panel = Panel.None } }

    var stats by remember { mutableStateOf(StreamStats(0f, 0f)) }
    LaunchedEffect(panel != Panel.None && panel != Panel.List) {
        while (panel != Panel.None && panel != Panel.List) { stats = player.sampleStats(); delay(1_000) }
    }

    var epg by remember(channel.streamId) { mutableStateOf<List<Programme>>(emptyList()) }
    LaunchedEffect(channel.streamId) { epg = vm.repo.epg(channel.streamId) }

    BackHandler {
        if (panel == Panel.Recents || panel == Panel.List) panel = Panel.None else vm.closePlayer()
    }

    val rootFocus = remember { FocusRequester() }
    LaunchedEffect(panel) {
        if (panel == Panel.None || panel == Panel.Info) { withFrameNanos { }; runCatching { rootFocus.requestFocus() } }
    }
    var centreLong by remember { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxSize()
            .focusRequester(rootFocus)
            .focusable()
            .onPreviewKeyEvent { ev ->
                // Once a menu is open, its own items handle the keys.
                if (panel == Panel.Recents || panel == Panel.List) return@onPreviewKeyEvent false
                val k = ev.nativeKeyEvent
                val centre = k.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || k.keyCode == KeyEvent.KEYCODE_ENTER ||
                    k.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
                if (k.action == KeyEvent.ACTION_DOWN) {
                    when {
                        k.keyCode == KeyEvent.KEYCODE_DPAD_UP || k.keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN -> { vm.zap(-1); true }
                        k.keyCode == KeyEvent.KEYCODE_DPAD_DOWN || k.keyCode == KeyEvent.KEYCODE_CHANNEL_UP -> { vm.zap(+1); true }
                        k.keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> { panel = Panel.List; true }
                        k.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || k.keyCode == KeyEvent.KEYCODE_LAST_CHANNEL -> {
                            vm.previousChannel()?.let { vm.tune(it) } ?: flashInfo(); true
                        }
                        k.keyCode == KeyEvent.KEYCODE_MENU -> { vm.toggleFavourite(channel.streamId); flashInfo(); true }
                        centre -> {
                            if (k.repeatCount == 1) { centreLong = true; vm.toggleFavourite(channel.streamId); flashInfo() }
                            true
                        }
                        else -> false
                    }
                } else if (k.action == KeyEvent.ACTION_UP && centre) {
                    if (!centreLong) {
                        if (vm.recentChannels().isNotEmpty()) panel = Panel.Recents
                        else if (panel == Panel.Info) panel = Panel.None else flashInfo()
                    }
                    centreLong = false
                    true
                } else false
            },
    ) {
        if (panel == Panel.Info || panel == Panel.Recents) {
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x99000000))))
                    .padding(start = 40.dp, end = 40.dp, top = 48.dp, bottom = 28.dp)
                    // A soft grey panel keeps the text readable over a busy, moving picture.
                    .clip(RoundedCornerShape(18.dp))
                    .background(Palette.panel.copy(alpha = 0.86f))
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                InfoBar(
                    num = channel.num, name = channel.name, icon = channel.icon,
                    isFavourite = channel.streamId in vm.favourites,
                    epg = epg, info = info, stats = stats,
                    display = matcher.currentMode.value, afr = vm.afrEnabled,
                )
                if (panel == Panel.Recents) {
                    RecentsRow(
                        vm,
                        onPick = { vm.tune(it); flashInfo() },
                        onClose = { panel = Panel.Info; nonce++ },
                    )
                }
            }
        }
        if (panel == Panel.List) {
            ChannelListOverlay(
                vm, current = channel,
                onPick = { picked ->
                    if (picked.streamId == channel.streamId) panel = Panel.None else { vm.tune(picked); flashInfo() }
                },
                onClose = { panel = Panel.None },
            )
        }
    }
}

@Composable
private fun RecentsRow(vm: AppViewModel, onPick: (Channel) -> Unit, onClose: () -> Unit) {
    val recents = remember(vm.recents) { vm.recentChannels() }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { first.requestFocus() } }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Recently watched", color = Palette.muted, style = MaterialTheme.typography.labelLarge)
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 6.dp),
            modifier = Modifier.onPreviewKeyEvent { ev ->
                val k = ev.nativeKeyEvent
                if (k.action == KeyEvent.ACTION_DOWN && k.keyCode == KeyEvent.KEYCODE_DPAD_UP) { onClose(); true } else false
            },
        ) {
            itemsIndexed(recents, key = { _, c -> c.streamId }) { i, c ->
                Surface(
                    onClick = { onPick(c) },
                    onLongClick = { vm.toggleFavourite(c.streamId) },
                    modifier = Modifier.width(200.dp).then(if (i == 0) Modifier.focusRequester(first) else Modifier),
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Color(0x33FFFFFF), contentColor = Palette.text,
                        focusedContainerColor = Palette.focus, focusedContentColor = Palette.onFocus,
                    ),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
                ) {
                    Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        AsyncImage(model = c.icon, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(width = 120.dp, height = 56.dp))
                        Text(c.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelListOverlay(vm: AppViewModel, current: Channel, onPick: (Channel) -> Unit, onClose: () -> Unit) {
    val list = vm.playlist
    val state = rememberLazyListState()
    var target by remember { mutableStateOf<Int?>(current.streamId) }
    LaunchedEffect(Unit) {
        val idx = list.indexOfFirst { it.streamId == current.streamId }
        if (idx >= 0) state.scrollToItem((idx - 3).coerceAtLeast(0))
    }
    Row(Modifier.fillMaxHeight()) {
        Column(
            Modifier.width(560.dp).fillMaxHeight().background(Palette.bg.copy(alpha = 0.94f))
                .padding(start = 40.dp, end = 20.dp, top = 28.dp, bottom = 20.dp)
                .onPreviewKeyEvent { ev ->
                    val k = ev.nativeKeyEvent
                    when {
                        k.action != KeyEvent.ACTION_DOWN -> false
                        k.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> { onClose(); true }
                        // Second Left: out to the full channel list with the groups showing.
                        k.keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> { vm.openGroupsRail(); true }
                        else -> false
                    }
                },
        ) {
            Text(vm.playlistLabel.ifEmpty { "Channels" }, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${list.size} channels  ·  Left again for groups", color = Palette.muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 12.dp))
            LazyColumn(state = state, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(list, key = { it.streamId }) { ch ->
                    ChannelRow(
                        vm = vm, channel = ch,
                        isFavourite = ch.streamId in vm.favourites,
                        isPlaying = ch.streamId == current.streamId,
                        focusMe = ch.streamId == target,
                        onFocused = { target = null },
                        onClick = { onPick(ch) },
                        onLongClick = { vm.toggleFavourite(ch.streamId) },
                    )
                }
            }
        }
        Box(
            Modifier.width(120.dp).fillMaxHeight()
                .background(Brush.horizontalGradient(listOf(Palette.bg.copy(alpha = 0.94f), Color.Transparent))),
        )
    }
}

@Composable
private fun InfoBar(
    num: Int, name: String, icon: String?, isFavourite: Boolean,
    epg: List<Programme>, info: StreamInfo, stats: StreamStats, display: String, afr: Boolean,
) {
    val t = System.currentTimeMillis()
    val now = epg.firstOrNull { it.isOn(t) }
    val next = epg.firstOrNull { it.startMs >= (now?.endMs ?: t) }
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        AsyncImage(model = icon, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(width = 120.dp, height = 72.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("$num", color = Palette.muted, style = MaterialTheme.typography.titleLarge)
                Text(name, color = Palette.text, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (isFavourite) Text("★", color = Palette.fav, style = MaterialTheme.typography.titleLarge)
            }
            if (now != null) {
                Text("${hm(now.startMs)} – ${hm(now.endMs)}   ${now.title}", color = Palette.text, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                Box(Modifier.fillMaxWidth(0.6f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x33FFFFFF))) {
                    Box(Modifier.fillMaxWidth(now.progress(t)).height(4.dp).background(Palette.accent))
                }
            } else {
                Text("No guide information", color = Palette.muted, style = MaterialTheme.typography.titleMedium)
            }
            next?.let { Text("Next  ${hm(it.startMs)}   ${it.title}", color = Palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val res = when {
                info.height >= 2000 -> "4K"
                info.height >= 1000 -> "1080"
                info.height >= 700 -> "720"
                info.height > 0 -> "SD"
                else -> null
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOfNotNull(res, info.videoCodec, info.hdr).forEach { Badge(it, highlight = it == info.hdr) }
            }
            val fps = if (info.fps > 0f) "%.3f".format(info.fps).trimEnd('0').trimEnd('.') + " fps" else "measuring fps…"
            Text("$fps  ·  TV ${display.substringAfter("@ ")}${if (afr) "" else " (match off)"}", color = Palette.muted, style = MaterialTheme.typography.bodySmall)
            val bufferColour = if (stats.bufferSec < 2f) Palette.error else Palette.muted
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("%.1f Mbps  ·".format(stats.mbps), color = Palette.muted, style = MaterialTheme.typography.bodySmall)
                Text("buffer %.1f s".format(stats.bufferSec), color = bufferColour, style = MaterialTheme.typography.bodySmall)
            }
            info.audio?.let { Text(it, color = Palette.muted, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun Badge(text: String, highlight: Boolean) {
    Box(
        Modifier.clip(RoundedCornerShape(6.dp))
            .background(if (highlight) Palette.accent else Color(0x33FFFFFF))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(text, color = if (highlight) Palette.bg else Palette.text, style = MaterialTheme.typography.labelMedium)
    }
}

private fun hm(ms: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms))
