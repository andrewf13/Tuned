package com.andrewf13.tuned.mobile.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.andrewf13.tuned.data.BackupFile
import com.andrewf13.tuned.data.guide.GuideSources
import com.andrewf13.tuned.mobile.BuildConfig
import com.andrewf13.tuned.mobile.MobileViewModel
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
private fun Section(title: String) = Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 20.dp, bottom = 4.dp))

@Composable
private fun Item(title: String, value: String = "", help: String? = null, onClick: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            help?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (value.isNotEmpty()) Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
    }
}

/** Settings: profiles, look, playback, groups, TV guide, backup. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsTab(vm: MobileViewModel) {
    var groups by remember { mutableStateOf(false) }
    if (groups) { GroupsChooser(vm) { groups = false }; return }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)

        Section("Providers")
        vm.profiles.forEach { p ->
            val active = p.id == vm.activeId
            Item(p.name + if (active) "  ✓" else "", if (active) "In use" else "Switch", p.summary) { if (!active) vm.switchProfile(p.id) }
            TextButton({ vm.editProfile(p) }) { Text("Edit ${p.name}") }
        }
        Item("+ Add a provider", help = "Xtream Codes or an M3U playlist", onClick = vm::addProfile)

        Section("Look")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("system" to "System", "light" to "Light", "dark" to "Dark").forEach { (k, l) -> FilterChip(vm.themeMode == k, { vm.chooseTheme(k) }, label = { Text(l) }) }
        }
        Item("Channel order", if (vm.sortAlpha) "A–Z" else "Provider", onClick = vm::toggleSort)
        Item("Choose groups", "${vm.view.categories.size} of ${vm.view.allCategories.size}", "Pick the groups that appear in Live TV") { groups = true }

        Section("Playback")
        Item("Buffer size", vm.bufferMode.replaceFirstChar { it.uppercase() }, "Small: quickest channel changes · Large: rides out a shaky connection", vm::cycleBuffer)
        Item("Volume boost", if (vm.boostDb == 0) "Off" else "+${vm.boostDb} dB", "Lifts quiet channels", vm::cycleBoost)
        Item("Stream format", if (vm.streamFormat == "ts") "MPEG-TS" else "HLS", "Try HLS if a channel won't play", vm::toggleFormat)

        Section("TV guide")
        Text("Your provider's guide plus free community guides (epgshare01) for these countries:", style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GuideSources.countries.forEach { c -> FilterChip(c.code in vm.guideCountries, { vm.toggleGuideCountry(c.code) }, label = { Text(c.name) }) }
        }
        val g = vm.guide
        Item(
            "Update now", if (vm.guideBusy) "Updating…" else "",
            g?.takeIf { it.updatedAt > 0 }?.let { "Guide for ${it.meta("keptCovered")} of ${it.meta("keptTotal")} channels · " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it.updatedAt)) }
                ?: "Not downloaded yet",
        ) { vm.refreshGuide(force = true) }

        Section("Backup & restore")
        BackupPanel(vm)

        Section("About")
        Text("Tuned ${BuildConfig.VERSION_NAME} for phones and tablets · Apache License 2.0 · guide data: your provider and epgshare01.online", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun GroupsChooser(vm: MobileViewModel, close: () -> Unit) {
    var filter by remember { mutableStateOf("") }
    var version by remember { mutableStateOf(0) }
    val list = remember(vm.view, filter, version) { vm.view.allCategories.filter { filter.isBlank() || it.name.contains(filter, true) } }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Groups", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(close) { Text("Done") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ vm.setAllGroups(true); version++ }) { Text("Tick all") }
            OutlinedButton({ vm.setAllGroups(false); version++ }) { Text("Untick all") }
        }
        OutlinedTextField(filter, { filter = it }, singleLine = true, placeholder = { Text("Filter, e.g. AU, NZ, sport") }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
        LazyColumn {
            items(list, key = { it.id }) { c ->
                Row(Modifier.fillMaxWidth().clickable { vm.toggleGroup(c.id); version++ }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(vm.isGroupShown(c.id), { vm.toggleGroup(c.id); version++ })
                    Text(c.name)
                }
            }
        }
    }
}

/** Encrypted backup of providers, favourites, groups and settings: the same file format as the TV app. */
@Composable
fun BackupPanel(vm: MobileViewModel) {
    val scope = rememberCoroutineScope()
    var pass by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    var files by remember { mutableStateOf<List<BackupFile>>(emptyList()) }
    var reload by remember { mutableStateOf(0) }
    LaunchedEffect(reload) { files = runCatching { vm.backups.list() }.getOrDefault(emptyList()) }
    val context = LocalContext.current
    fun restore(bytes: suspend () -> ByteArray) {
        if (pass.length < 6) { msg = "Enter the backup's passphrase first"; return }
        scope.launch { msg = try { vm.restoreBackup(bytes(), pass); "Restored" } catch (e: Exception) { e.message ?: "Restore failed" } }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) restore { context.contentResolver.openInputStream(uri)!!.use { it.readBytes() } }
    }
    Text("Move to or from the TV app: save on one, copy the .tunedbak file across, restore on the other.", style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(pass, { pass = it }, singleLine = true, label = { Text("Passphrase") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
        if (vm.profiles.isNotEmpty()) Button({
            if (pass.length < 6) msg = "Choose a passphrase of at least 6 characters"
            else scope.launch { msg = try { "Saved to " + vm.saveBackup(pass) } catch (e: Exception) { e.message }; reload++ }
        }) { Text("Save backup") }
        OutlinedButton({ picker.launch(arrayOf("*/*")) }) { Text("Restore from file…") }
    }
    msg?.let { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp)) }
    files.forEach { f ->
        Item(f.name, "Restore", DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(f.modifiedMs))) { restore { vm.backups.read(f.uri) } }
    }
}
