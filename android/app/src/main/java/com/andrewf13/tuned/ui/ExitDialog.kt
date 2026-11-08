package com.andrewf13.tuned.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

/** "Exit Tuned?" Focus starts on Stay, so an accidental hold of Back needs a deliberate second step. */
@Composable
fun ExitDialog(onExit: () -> Unit, onStay: () -> Unit) {
    Dialog(onDismissRequest = onStay, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val stay = remember { FocusRequester() }
        LaunchedEffect(Unit) { stay.requestWhenReady() }
        Column(
            Modifier.width(520.dp).clip(RoundedCornerShape(16.dp)).background(Palette.panel).padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Exit Tuned?", style = MaterialTheme.typography.headlineSmall, color = Palette.text)
            Text("Playback will stop.", color = Palette.muted)
            Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                FocusCard(onClick = onStay, modifier = Modifier.focusRequester(stay), selected = true) {
                    Text("Stay", style = MaterialTheme.typography.titleMedium)
                }
                FocusCard(onClick = onExit) { Text("Exit", style = MaterialTheme.typography.titleMedium) }
            }
        }
    }
}
