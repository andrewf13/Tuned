package com.andrewf13.tuned.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.andrewf13.tuned.AppViewModel
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(vm: AppViewModel) {
    var server by rememberSaveable { mutableStateOf("") }
    var user by rememberSaveable { mutableStateOf("") }
    var pass by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.width(620.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Tuned", style = MaterialTheme.typography.displaySmall)
            Text("Sign in with your Xtream Codes details.", color = Palette.muted)
            OutlinedTextField(
                value = server, onValueChange = { server = it }, singleLine = true,
                label = { androidx.compose.material3.Text("Server (e.g. http://example.com:8080)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = user, onValueChange = { user = it }, singleLine = true,
                label = { androidx.compose.material3.Text("Username") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = pass, onValueChange = { pass = it }, singleLine = true,
                label = { androidx.compose.material3.Text("Password") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Tip: a full M3U link (get.php?username=…&password=…) also works in the Server box.",
                color = Palette.muted, style = MaterialTheme.typography.bodySmall,
            )
            FocusCard(onClick = {
                if (busy) return@FocusCard
                scope.launch {
                    busy = true; error = null
                    error = vm.login(server, user, pass)
                    busy = false
                }
            }, selected = true) {
                Text(if (busy) "Signing in…" else "Sign in", style = MaterialTheme.typography.titleMedium)
            }
            error?.let { Text(it, color = Palette.error) }
            FocusCard(onClick = vm::openBackup) { Text("Restore from a backup instead") }
        }
    }
}
