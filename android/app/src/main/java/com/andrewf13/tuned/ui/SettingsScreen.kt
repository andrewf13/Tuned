package com.andrewf13.tuned.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.andrewf13.tuned.AppViewModel
import com.andrewf13.tuned.data.AccountInfo
import com.andrewf13.tuned.player.RefreshRateMatcher
import java.text.DateFormat
import java.util.Date

@Composable
fun SettingsScreen(vm: AppViewModel, matcher: RefreshRateMatcher, onExit: () -> Unit = {}) {
    BackHandler { vm.backToHome() }
    val account by produceState<AccountInfo?>(null) { value = vm.accountInfo() }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    Column(Modifier.fillMaxSize().padding(horizontal = 96.dp, vertical = 40.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.size(20.dp))
        LazyColumn(Modifier.width(820.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item {
                SettingRow(
                    "Match frame rate", if (vm.afrEnabled) "On" else "Off",
                    "Switches the TV to 23.976 / 24 / 50 / 59.94 Hz to suit each channel. On the Shield, also turn on " +
                        "Settings → Display & Sound → Advanced → Match content frame rate.",
                    Modifier.focusRequester(first), vm::toggleAfr,
                )
            }
            item {
                SettingRow(
                    "Colour", Palette.theme.name,
                    "Graphite grey, Midnight blue, Pitch green or Aurora violet. OK to cycle.",
                    onClick = vm::cycleTheme,
                )
            }
            item {
                SettingRow(
                    "Stream format", if (vm.streamFormat == "ts") "MPEG-TS" else "HLS",
                    "MPEG-TS usually starts fastest. Try HLS if a channel won't play.",
                    onClick = vm::toggleFormat,
                )
            }
            item {
                SettingRow(
                    "Volume boost", if (vm.volumeBoostDb == 0) "Off" else "+${vm.volumeBoostDb} dB",
                    "Lifts quiet channels, with a limiter so loud ones don't clip. Works on audio the Shield decodes " +
                        "(AAC/MP2 stereo, most quiet channels). Dolby audio passed straight to a soundbar isn't changed.",
                    onClick = vm::cycleBoost,
                )
            }
            item {
                SettingRow(
                    "Channel order", if (vm.sortAlpha) "A–Z" else "Provider",
                    "A–Z sorts every group, Favourites and Search by channel name.",
                    onClick = vm::toggleSort,
                )
            }
            item {
                val shown = vm.allCategories.count { vm.isGroupShown(it.id) }
                SettingRow("Choose groups", "$shown of ${vm.allCategories.size}", "Hide the groups you never use.", onClick = vm::openGroups)
            }
            item {
                SettingRow("Open last channel on start", if (vm.resumeOnStart) "On" else "Off", null, onClick = vm::toggleResume)
            }
            item {
                SettingRow("Refresh channel list", if (vm.refreshing) "Updating…" else "", vm.status, onClick = vm::refresh)
            }
            item { SettingRow("Backup & restore", "", "Save your login, favourites and settings to an encrypted file, or restore one.", onClick = vm::openBackup) }
            item { SettingRow("Exit Tuned", "", "Stops playback and closes the app. Holding Back anywhere does the same.", onClick = onExit) }
            item { SettingRow("Sign out", "", "Removes your login and saved channel list from this device.", onClick = vm::signOut) }

            item { Section("Account") }
            item {
                val a = account
                val lines = if (a == null) listOf("Checking…") else listOfNotNull(
                    a.status?.let { "Status: $it" },
                    a.expiresMs?.let { "Expires: ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it))}" },
                    a.maxConnections?.let { "Connections: ${a.activeConnections ?: "?"} of $it in use" },
                )
                lines.forEach { Text(it, color = Palette.muted, modifier = Modifier.padding(start = 16.dp)) }
            }

            item { Section("Display") }
            item {
                Column(Modifier.padding(start = 16.dp)) {
                    Text("Now: ${matcher.currentMode.value}", color = Palette.text)
                    Text("Modes the TV offers:", color = Palette.muted, modifier = Modifier.padding(top = 6.dp))
                    matcher.supportedModes().forEach { Text("  $it", color = Palette.muted, style = MaterialTheme.typography.bodySmall) }
                }
            }
            item { Text("Tuned ${com.andrewf13.tuned.BuildConfig.VERSION_NAME}", color = Palette.muted, modifier = Modifier.padding(start = 16.dp, top = 24.dp)) }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = Palette.accent, modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 6.dp))
}

@Composable
private fun SettingRow(title: String, value: String, help: String?, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FocusCard(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            help?.let { Text(it, color = mutedColor(), style = MaterialTheme.typography.bodySmall) }
        }
        if (value.isNotEmpty()) Text(value, style = MaterialTheme.typography.titleMedium)
    }
}
