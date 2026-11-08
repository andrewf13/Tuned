package com.andrewf13.tuned.mobile.ui

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.provider.Settings
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.andrewf13.tuned.data.Channel
import com.andrewf13.tuned.data.Programme
import com.andrewf13.tuned.mobile.MainActivity
import com.andrewf13.tuned.mobile.MobileViewModel
import com.andrewf13.tuned.player.PlayerController
import com.andrewf13.tuned.player.StreamStats
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs

private fun hm(ms: Long) = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms))

/**
 * Watching. Phone portrait: video on top, channel list below. Phone landscape (or the full-screen
 * button): full screen with touch controls and brightness/volume sliders. Tablet landscape:
 * video with the channel list beside it.
 */
@Composable
fun PlayerScreen(vm: MobileViewModel, player: PlayerController, activity: MainActivity, inPip: Boolean) {
    val channel = vm.playing ?: return
    val url = vm.streamUrl(channel)
    LaunchedEffect(url) {
        delay(250) // fast channel flicking doesn't tune every channel
        player.play(url)
        delay(5_000)
        vm.markWatched(channel.streamId)
    }
    var epg by remember(channel.streamId) { mutableStateOf<List<Programme>>(emptyList()) }
    LaunchedEffect(channel.streamId, vm.guideVersion) { epg = vm.repo.epg(channel.streamId) }

    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = false
            player.stop()
            activity.window.attributes = activity.window.attributes.apply { screenBrightness = -1f } // back to the system brightness
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    val cfg = LocalConfiguration.current
    val landscape = cfg.orientation == Configuration.ORIENTATION_LANDSCAPE
    val tablet = cfg.smallestScreenWidthDp >= 600
    val full = inPip || vm.fullscreen || (landscape && !tablet)

    // Hide the status and navigation bars while full screen (swipe from an edge to peek).
    DisposableEffect(full) {
        val c = WindowCompat.getInsetsController(activity.window, view)
        if (full) {
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.systemBars())
        } else c.show(WindowInsetsCompat.Type.systemBars())
        onDispose { c.show(WindowInsetsCompat.Type.systemBars()) }
    }

    fun setFullscreen(on: Boolean) {
        vm.fullscreen = on
        if (!tablet) activity.requestedOrientation =
            if (on) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
    }
    BackHandler { if (full && !inPip && (vm.fullscreen || landscape && !tablet)) setFullscreen(false) else vm.closePlayer() }

    var resize by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }

    when {
        inPip -> VideoSurface(player, resize, Modifier.fillMaxSize())
        full -> FullscreenPlayer(vm, player, channel, epg, resize, onResize = { resize = nextResize(resize) }, onExit = { setFullscreen(false) }, onPip = activity::enterPip)
        tablet && landscape -> Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Box(Modifier.fillMaxWidth().aspectRatio(16 / 9f)) { InlineVideo(vm, player, resize, onFull = { setFullscreen(true) }, onPip = activity::enterPip) }
                NowNext(vm, channel, epg, player)
            }
            ChannelPanel(vm, channel, Modifier.width(380.dp).fillMaxHeight())
        }
        else -> Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
            Box(Modifier.fillMaxWidth().aspectRatio(16 / 9f)) { InlineVideo(vm, player, resize, onFull = { setFullscreen(true) }, onPip = activity::enterPip) }
            NowNext(vm, channel, epg, player)
            HorizontalDivider()
            ChannelPanel(vm, channel, Modifier.weight(1f))
        }
    }
}

private fun nextResize(m: Int) = when (m) {
    AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL
    else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
}
private fun resizeLabel(m: Int) = when (m) { AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "Zoom"; AspectRatioFrameLayout.RESIZE_MODE_FILL -> "Stretch"; else -> "Fit" }

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun VideoSurface(player: PlayerController, resize: Int, modifier: Modifier) {
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                setKeepContentOnPlayerReset(true)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
        },
        update = { it.player = player.exo; it.resizeMode = resize },
        modifier = modifier.background(Color.Black),
    )
}

/** "Buffering…" and, if it drags on, what's wrong (internet, provider or the channel). */
@Composable
private fun StallBadge(vm: MobileViewModel, player: PlayerController, modifier: Modifier) {
    val since by player.stallSince.collectAsState()
    val error by player.error.collectAsState()
    var secs by remember { mutableIntStateOf(0) }
    var stats by remember { mutableStateOf(StreamStats(0f, 0f)) }
    var verdict by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(since, error) {
        verdict = null
        if (since == null && error == null) return@LaunchedEffect
        var asked = false
        while (true) {
            stats = player.sampleStats()
            secs = since?.let { ((android.os.SystemClock.elapsedRealtime() - it) / 1000).toInt() } ?: 0
            if (!asked && (error != null || secs >= 5)) {
                asked = true
                verdict = vm.doctor.diagnose(vm.repo.profile, vm.repo.client, stats.mbps.takeIf { error == null }).let { "${it.headline}. ${it.advice}" }
            }
            delay(1_000)
        }
    }
    if (since == null && error == null) return
    Column(
        modifier.padding(24.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xCC000000)).padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(error ?: if (secs < 3) "Loading…" else "Buffering…", color = Color.White, fontWeight = FontWeight.SemiBold)
        if (error == null) Text("%.1f Mbps · %.1f s buffered".format(stats.mbps, stats.bufferSec), color = Color(0xFFB8C0CC), style = MaterialTheme.typography.bodySmall)
        verdict?.let { Text(it, color = Color.White, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)) }
    }
}

/** The video in portrait / tablet layouts, with a small control strip on tap. */
@Composable
private fun InlineVideo(vm: MobileViewModel, player: PlayerController, resize: Int, onFull: () -> Unit, onPip: () -> Unit) {
    var controls by remember { mutableStateOf(true) }
    var nonce by remember { mutableIntStateOf(0) }
    LaunchedEffect(nonce) { delay(3_500); controls = false }
    Box(Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) { detectTapGestures { controls = !controls; nonce++ } }) {
        VideoSurface(player, resize, Modifier.fillMaxSize())
        StallBadge(vm, player, Modifier.align(Alignment.Center))
        AnimatedVisibility(controls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().background(Color(0x55000000))) {
                Row(Modifier.align(Alignment.TopStart).padding(8.dp)) { RoundButton("←") { vm.closePlayer() } }
                Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    RoundButton("⏮", big = true) { vm.zap(-1); nonce++ }
                    RoundButton("⏭", big = true) { vm.zap(+1); nonce++ }
                }
                Row(Modifier.align(Alignment.BottomEnd).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RoundButton("▣", onClick = onPip)
                    RoundButton("⛶", onClick = onFull)
                }
            }
        }
    }
}

@Composable
private fun RoundButton(glyph: String, big: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.size(if (big) 56.dp else 42.dp).clip(CircleShape).background(Color(0x99000000)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(glyph, color = Color.White, fontSize = if (big) 24.sp else 18.sp) }
}

/** Channel name, what's on now (with progress) and next, plus picture details. */
@Composable
private fun NowNext(vm: MobileViewModel, c: Channel, epg: List<Programme>, player: PlayerController) {
    val info by player.info.collectAsState()
    val t = System.currentTimeMillis()
    val now = epg.firstOrNull { it.isOn(t) }
    val next = epg.firstOrNull { it.startMs >= (now?.endMs ?: t) }
    val fav = c.streamId in vm.favourites
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(c.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(if (fav) "★" else "☆", color = if (fav) Star else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 26.sp,
                modifier = Modifier.clip(CircleShape).clickable { vm.toggleFavourite(c.streamId) }.padding(6.dp))
        }
        if (now != null) {
            Text("${hm(now.startMs)}–${hm(now.endMs)}  ${now.title}", style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            LinearProgressIndicator(progress = { now.progress(t) }, modifier = Modifier.fillMaxWidth().height(3.dp))
        }
        next?.let { Text("Next ${hm(it.startMs)}  ${it.title}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        val badges = listOfNotNull(
            when { info.height >= 2000 -> "4K"; info.height >= 1000 -> "1080p"; info.height >= 700 -> "720p"; info.height > 0 -> "SD"; else -> null },
            info.videoCodec, info.hdr, info.fps.takeIf { it > 0 }?.let { "%.0f fps".format(it) }, info.audio,
        )
        if (badges.isNotEmpty()) Text(badges.joinToString("  ·  "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The list the channel came from, to switch without leaving the player. */
@Composable
private fun ChannelPanel(vm: MobileViewModel, current: Channel, modifier: Modifier) {
    val state = rememberLazyListState()
    LaunchedEffect(current.streamId) {
        val i = vm.playlist.indexOfFirst { it.streamId == current.streamId }
        if (i > 2) state.animateScrollToItem(i - 2)
    }
    LazyColumn(modifier, state = state) {
        items(vm.playlist, key = { it.streamId }) { c ->
            ChannelRow(vm, c, playing = c.streamId == current.streamId, onClick = { vm.tune(c) }, onLongClick = { vm.toggleFavourite(c.streamId) })
        }
    }
}

// ---- Full screen ---------------------------------------------------------------------------

private fun Context.audio() = getSystemService(Context.AUDIO_SERVICE) as AudioManager

/**
 * Full screen with touch controls: tap shows the controls; on the far left a brightness slider
 * and on the far right a volume slider (or drag up/down anywhere on that side); swipe sideways
 * in the middle to change channel.
 */
@Composable
private fun FullscreenPlayer(
    vm: MobileViewModel, player: PlayerController, c: Channel, epg: List<Programme>, resize: Int,
    onResize: () -> Unit, onExit: () -> Unit, onPip: () -> Unit,
) {
    val ctx = LocalContext.current
    val activity = ctx as MainActivity
    val audio = remember { ctx.audio() }
    val maxVol = remember { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var volume by remember { mutableFloatStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC) / maxVol.toFloat()) }
    var brightness by remember {
        mutableFloatStateOf(
            activity.window.attributes.screenBrightness.takeIf { it >= 0f }
                ?: (runCatching { Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f }.getOrDefault(0.5f)),
        )
    }
    fun applyVolume(v: Float) {
        volume = v.coerceIn(0f, 1f)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (volume * maxVol).toInt(), 0)
    }
    fun applyBrightness(b: Float) {
        brightness = b.coerceIn(0.02f, 1f)
        activity.window.attributes = activity.window.attributes.apply { screenBrightness = brightness }
    }

    var controls by remember { mutableStateOf(true) }
    var nonce by remember { mutableIntStateOf(0) }
    var adjusting by remember { mutableStateOf<String?>(null) } // "brightness" or "volume" while dragging
    var swipe by remember { mutableFloatStateOf(0f) }
    var panel by remember { mutableStateOf(false) }
    LaunchedEffect(nonce, panel) { if (!panel) { delay(4_000); controls = false } }
    LaunchedEffect(c.streamId) { controls = true; nonce++ }
    val zapPx = with(LocalDensity.current) { 110.dp.toPx() }
    val info by player.info.collectAsState()

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        VideoSurface(player, resize, Modifier.fillMaxSize())
        // Gestures over the whole picture.
        Box(
            Modifier.fillMaxSize()
                .pointerInput(Unit) { detectTapGestures { controls = !controls; nonce++ } }
                .pointerInput(w, h) {
                    var zone = ""
                    detectDragGestures(
                        onDragStart = { o -> zone = when { o.x < w * 0.33f -> "brightness"; o.x > w * 0.67f -> "volume"; else -> "zap" }; swipe = 0f },
                        onDragEnd = {
                            if (zone == "zap" && abs(swipe) > zapPx) vm.zap(if (swipe < 0) +1 else -1)
                            adjusting = null; swipe = 0f
                        },
                        onDragCancel = { adjusting = null; swipe = 0f },
                    ) { _, d ->
                        when (zone) {
                            "brightness" -> { adjusting = zone; applyBrightness(brightness - d.y / (h * 0.8f)) }
                            "volume" -> { adjusting = zone; applyVolume(volume - d.y / (h * 0.8f)) }
                            else -> swipe += d.x
                        }
                    }
                },
        )
        StallBadge(vm, player, Modifier.align(Alignment.Center))

        // The two side sliders: always while adjusting, and whenever the controls are up.
        val showSliders = controls || adjusting != null
        AnimatedVisibility(showSliders, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.CenterStart)) {
            SideSlider("☀", brightness, Modifier.padding(start = 20.dp)) { applyBrightness(it); nonce++ }
        }
        AnimatedVisibility(showSliders, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.CenterEnd)) {
            SideSlider(if (volume == 0f) "🔇" else "🔊", volume, Modifier.padding(end = 20.dp)) { applyVolume(it); nonce++ }
        }

        AnimatedVisibility(controls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            val t = System.currentTimeMillis()
            val now = epg.firstOrNull { it.isOn(t) }
            Row(
                Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RoundButton("←", onClick = onExit)
                Column(Modifier.weight(1f)) {
                    Text(c.name, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    now?.let { Text("${hm(it.startMs)}–${hm(it.endMs)}  ${it.title}", color = Color(0xFFD0D6DE), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                Text(java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(Date(t)), color = Color.White, style = MaterialTheme.typography.titleMedium)
                val fav = c.streamId in vm.favourites
                RoundButton(if (fav) "★" else "☆") { vm.toggleFavourite(c.streamId); nonce++ }
                RoundButton("▣", onClick = onPip)
            }
        }
        AnimatedVisibility(controls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            Row(
                Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
                    .padding(horizontal = 24.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                RoundButton("⏮") { vm.zap(-1); nonce++ }
                RoundButton("⏭") { vm.zap(+1); nonce++ }
                Text(
                    listOfNotNull(
                        when { info.height >= 2000 -> "4K"; info.height >= 1000 -> "1080p"; info.height >= 700 -> "720p"; info.height > 0 -> "SD"; else -> null },
                        info.hdr, info.audio,
                    ).joinToString("  ·  "),
                    color = Color(0xFFD0D6DE), style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f),
                )
                Pill(resizeLabel(resize)) { onResize(); nonce++ }
                Pill("Channels") { panel = !panel; controls = true }
            }
        }
        // Channel list over the right side of the picture.
        AnimatedVisibility(panel, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.CenterEnd)) {
            Surface(Modifier.width(340.dp).fillMaxHeight().padding(vertical = 12.dp, horizontal = 12.dp), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)) {
                Column {
                    Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Channels", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Text("✕", fontSize = 20.sp, modifier = Modifier.clip(CircleShape).clickable { panel = false }.padding(10.dp))
                    }
                    ChannelPanel(vm, c, Modifier.fillMaxSize())
                }
            }
        }
        // Big readout while dragging on a side.
        adjusting?.let { a ->
            val v = if (a == "volume") volume else brightness
            Text(
                (if (a == "volume") "🔊 " else "☀ ") + "${(v * 100).toInt()}%",
                color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 72.dp).clip(RoundedCornerShape(12.dp)).background(Color(0x99000000)).padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun Pill(text: String, onClick: () -> Unit) {
    Text(
        text, color = Color.White, style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(Color(0x66FFFFFF)).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/** A slim vertical slider: drag or tap on it to set 0–100%. */
@Composable
private fun SideSlider(icon: String, value: Float, modifier: Modifier, onChange: (Float) -> Unit) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(icon, fontSize = 20.sp, color = Color.White)
        BoxWithConstraints(
            // Light, see-through track so it reads on dark and bright pictures alike.
            Modifier.width(36.dp).height(180.dp).clip(RoundedCornerShape(18.dp)).background(Color(0x59FFFFFF))
                .border(1.dp, Color(0x80FFFFFF), RoundedCornerShape(18.dp))
                .pointerInput(Unit) { detectTapGestures { o -> onChange(1f - o.y / size.height) } }
                .pointerInput(Unit) { detectVerticalDragGestures { change, _ -> onChange(1f - change.position.y / size.height) } },
            contentAlignment = Alignment.BottomCenter,
        ) {
            Box(Modifier.fillMaxWidth().fillMaxHeight(value.coerceIn(0f, 1f)).background(Color(0xE6FFFFFF)))
        }
        Text("${(value * 100).toInt()}", color = Color.White, style = MaterialTheme.typography.labelSmall)
    }
}
