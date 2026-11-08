package com.andrewf13.tuned.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.andrewf13.tuned.AppViewModel
import com.andrewf13.tuned.data.SourceType
import kotlinx.coroutines.launch

/**
 * Add or edit a provider profile: Xtream Codes (server, username, password) or an M3U
 * playlist link. Also the first-run screen. Every box opens the keyboard only on OK.
 */
@Composable
fun LoginScreen(vm: AppViewModel) {
    val editing = vm.editingProfile
    val firstRun = vm.profiles.isEmpty()
    BackHandler(enabled = !firstRun) { vm.closeProfileForm() }

    var type by rememberSaveable { mutableStateOf(editing?.type ?: SourceType.XTREAM) }
    var name by rememberSaveable { mutableStateOf(editing?.name ?: "") }
    var server by rememberSaveable { mutableStateOf(editing?.server ?: "") }
    var user by rememberSaveable { mutableStateOf(editing?.username ?: "") }
    var pass by rememberSaveable { mutableStateOf(editing?.password ?: "") }
    var m3u by rememberSaveable { mutableStateOf(editing?.m3uUrl ?: "") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestWhenReady() }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.width(680.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                when { firstRun -> "Welcome to Tuned"; editing != null -> "Edit ${editing.name}"; else -> "Add a provider" },
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                if (type == SourceType.XTREAM) "Xtream Codes: your provider's server address, username and password."
                else "M3U playlist: the playlist link from your provider (for providers without Xtream Codes).",
                color = Palette.muted,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FocusCard(onClick = { type = SourceType.XTREAM; error = null }, selected = type == SourceType.XTREAM, modifier = Modifier.weight(1f)) {
                    Text((if (type == SourceType.XTREAM) "✓  " else "") + "Xtream Codes")
                }
                FocusCard(onClick = { type = SourceType.M3U; error = null }, selected = type == SourceType.M3U, modifier = Modifier.weight(1f)) {
                    Text((if (type == SourceType.M3U) "✓  " else "") + "M3U playlist")
                }
            }
            SearchBox(name, { name = it }, "Profile name (optional, e.g. \"Main\" or \"Sports\")", Modifier.fillMaxWidth(), buttonFocus = first)
            if (type == SourceType.XTREAM) {
                SearchBox(server, { server = it }, "Server (e.g. http://example.com:8080)", Modifier.fillMaxWidth())
                SearchBox(user, { user = it }, "Username", Modifier.fillMaxWidth())
                SearchBox(pass, { pass = it }, "Password", Modifier.fillMaxWidth(), secret = true)
                Text("Tip: a full get.php link (…?username=…&password=…) in the Server box fills in the rest.", color = Palette.muted, style = MaterialTheme.typography.bodySmall)
            } else {
                SearchBox(m3u, { m3u = it }, "Playlist link (http://…/playlist.m3u)", Modifier.fillMaxWidth())
                Text("M3U playlists don't carry a TV guide through this link; channels, groups and favourites all work.", color = Palette.muted, style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                FocusCard(onClick = {
                    if (busy) return@FocusCard
                    scope.launch {
                        busy = true; error = null
                        error = vm.saveProfile(name, type, server, user, pass, m3u)
                        busy = false
                    }
                }, selected = true) {
                    Text(if (busy) "Checking…" else if (editing != null) "Save" else "Connect", style = MaterialTheme.typography.titleMedium)
                }
                if (!firstRun) FocusCard(onClick = vm::closeProfileForm) { Text("Cancel") }
                if (editing != null) {
                    FocusCard(onClick = { if (confirmDelete) vm.deleteProfile(editing.id) else confirmDelete = true }) {
                        Text(if (confirmDelete) "OK again to delete" else "Delete profile", color = if (confirmDelete) Palette.error else androidx.tv.material3.LocalContentColor.current)
                    }
                }
                if (firstRun) FocusCard(onClick = vm::openBackup) { Text("Restore from a backup instead") }
            }
            error?.let { Text(it, color = Palette.error) }
        }
    }
}
