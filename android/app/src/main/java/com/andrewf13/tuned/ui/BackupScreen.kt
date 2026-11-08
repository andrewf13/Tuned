package com.andrewf13.tuned.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.andrewf13.tuned.AppViewModel
import com.andrewf13.tuned.data.BackupFile
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * Save an encrypted backup (login, favourites, groups, settings) to Download/Tuned,
 * or restore one. Also reachable from the sign-in screen, for setting up a new device.
 */
@Composable
fun BackupScreen(vm: AppViewModel, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val signedIn = vm.repo.client != null

    var passphrase by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var statusIsError by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var files by remember { mutableStateOf<List<BackupFile>>(emptyList()) }
    var pending by remember { mutableStateOf<Uri?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val first = remember { FocusRequester() }

    fun say(msg: String, error: Boolean = false) { status = msg; statusIsError = error }

    LaunchedEffect(reload) { files = vm.backups.list() }
    LaunchedEffect(Unit) { first.requestWhenReady() }

    fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    fun doRestore(load: suspend () -> ByteArray) {
        if (passphrase.length < 6) { say("Enter the backup's passphrase first (at least 6 characters).", true); return }
        scope.launch {
            busy = true
            say("Restoring…")
            try {
                vm.restoreBackup(load(), passphrase)
            } catch (e: Exception) {
                say(e.message ?: "Restore failed", true)
            } finally {
                busy = false
            }
        }
    }

    fun doSave() {
        if (passphrase.length < 6) { say("Choose a passphrase of at least 6 characters first.", true); return }
        scope.launch {
            busy = true
            say("Encrypting…")
            try {
                say("Saved to ${vm.saveBackup(passphrase)}")
                reload++
            } catch (e: Exception) {
                say("Couldn't save: ${e.message}", true)
            } finally {
                busy = false
            }
        }
    }

    val writePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) doSave() else say("Storage permission is needed to save to Download on this device.", true)
    }
    val readPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { reload++ }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) doRestore { vm.backups.read(uri) }
    }
    val hasPicker = remember {
        Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .resolveActivity(context.packageManager) != null
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 96.dp, vertical = 36.dp)) {
        Text("Backup & restore", style = MaterialTheme.typography.headlineMedium)
        Text(
            "A backup holds your login (server, username and password), favourites, chosen groups, recent channels and settings. " +
                "It's encrypted with your passphrase (AES-256). Without the passphrase it can't be opened, by anyone, including you.",
            color = Palette.muted, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp, bottom = 18.dp).width(900.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.width(900.dp)) {
            SearchBox(
                query = passphrase, onQuery = { passphrase = it; pending = null }, placeholder = "Passphrase",
                modifier = Modifier.weight(1f), buttonFocus = first, secret = true,
            )
            if (signedIn) {
                FocusCard(onClick = {
                    if (busy) return@FocusCard
                    if (vm.backups.needsWritePermission && !granted(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                        writePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    } else doSave()
                }, selected = true) { Text("Save backup") }
            }
        }
        status?.let {
            Text(it, color = if (statusIsError) Palette.error else Palette.accent, modifier = Modifier.padding(top = 12.dp))
        }

        Row(Modifier.padding(top = 26.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Backups in ${vm.backups.folderLabel}", style = MaterialTheme.typography.titleMedium, color = Palette.accent)
            if (hasPicker) FocusCard(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Choose a file…") }
            if (vm.backups.canAskReadPermission && !granted(Manifest.permission.READ_EXTERNAL_STORAGE)) {
                FocusCard(onClick = { readPermission.launch(Manifest.permission.READ_EXTERNAL_STORAGE) }) { Text("Find older backups") }
            }
        }
        if (files.isEmpty()) {
            Text("None found on this device yet.", color = Palette.muted, modifier = Modifier.padding(start = 16.dp))
        }
        LazyColumn(Modifier.width(900.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(files, key = { it.uri.toString() }) { f ->
                val armed = pending == f.uri
                FocusCard(onClick = {
                    if (busy) return@FocusCard
                    if (armed) doRestore { vm.backups.read(f.uri) }
                    else {
                        pending = f.uri
                        say(
                            if (signedIn) "Press OK again to restore. This replaces your current login, favourites and settings."
                            else "Press OK again to restore this backup.",
                        )
                    }
                }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text(f.name, style = MaterialTheme.typography.titleMedium)
                        Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(f.modifiedMs)), color = mutedColor(), style = MaterialTheme.typography.bodySmall)
                    }
                    Text(if (armed) "OK to restore" else "Restore", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}
