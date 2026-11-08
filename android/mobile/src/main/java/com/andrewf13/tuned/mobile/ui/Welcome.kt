package com.andrewf13.tuned.mobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.andrewf13.tuned.data.SourceType
import com.andrewf13.tuned.mobile.MobileViewModel
import kotlinx.coroutines.launch

/** First run: add a provider, or restore a backup (e.g. one saved on the TV). */
@Composable
fun WelcomeScreen(vm: MobileViewModel) {
    var restoring by remember { mutableStateOf(false) }
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Tuned", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.SemiBold)
            Text("A fast, simple IPTV player. It comes with no channels: sign in with your provider.", style = MaterialTheme.typography.bodyLarge)
            if (!restoring) {
                Button(vm::addProfile, Modifier.fillMaxWidth().widthIn(max = 420.dp)) { Text("Add my provider") }
                OutlinedButton({ restoring = true }, Modifier.fillMaxWidth().widthIn(max = 420.dp)) { Text("Restore a backup (e.g. from the TV app)") }
            } else {
                Column(Modifier.widthIn(max = 520.dp)) { BackupPanel(vm) }
                TextButton({ restoring = false }) { Text("Back") }
            }
        }
    }
}

/** Add or edit a provider: Xtream Codes or an M3U playlist link. */
@Composable
fun ProfileFormScreen(vm: MobileViewModel) {
    BackHandler(onBack = vm::closeProfileForm)
    val e = vm.editing
    var type by rememberSaveable { mutableStateOf(e?.type ?: SourceType.XTREAM) }
    var name by rememberSaveable { mutableStateOf(e?.name ?: "") }
    var server by rememberSaveable { mutableStateOf(e?.server ?: "") }
    var user by rememberSaveable { mutableStateOf(e?.username ?: "") }
    var pass by rememberSaveable { mutableStateOf(e?.password ?: "") }
    var m3u by rememberSaveable { mutableStateOf(e?.m3uUrl ?: "") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp).widthIn(max = 560.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(if (e != null) "Edit ${e.name}" else "Add a provider", style = MaterialTheme.typography.headlineSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(type == SourceType.XTREAM, { type = SourceType.XTREAM }, label = { Text("Xtream Codes") })
                FilterChip(type == SourceType.M3U, { type = SourceType.M3U }, label = { Text("M3U playlist") })
            }
            OutlinedTextField(name, { name = it }, label = { Text("Name (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (type == SourceType.XTREAM) {
                OutlinedTextField(server, { server = it }, label = { Text("Server, e.g. http://example.com:8080") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(user, { user = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(pass, { pass = it }, label = { Text("Password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                Text("Tip: paste a full get.php?username=…&password=… link into Server and the rest fills itself.", style = MaterialTheme.typography.bodySmall)
            } else {
                OutlinedTextField(m3u, { m3u = it }, label = { Text("Playlist link") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ scope.launch { busy = true; error = vm.saveProfile(name, type, server, user, pass, m3u); busy = false } }, enabled = !busy) {
                    Text(if (busy) "Checking…" else if (e != null) "Save" else "Connect")
                }
                TextButton(vm::closeProfileForm) { Text("Cancel") }
                if (e != null) TextButton({ if (confirmDelete) vm.deleteProfile(e.id) else confirmDelete = true }) { Text(if (confirmDelete) "Tap again to delete" else "Delete") }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}
