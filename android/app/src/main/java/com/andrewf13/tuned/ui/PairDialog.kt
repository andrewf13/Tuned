package com.andrewf13.tuned.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

/** Shown when a phone asks to pair: the code to type on the phone. */
@Composable
fun PairDialog(device: String, code: String, onCancel: () -> Unit) {
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val cancel = remember { FocusRequester() }
        LaunchedEffect(Unit) { cancel.requestWhenReady() }
        Column(
            Modifier.width(560.dp).clip(RoundedCornerShape(16.dp)).background(Palette.panel).padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Pair $device?", style = MaterialTheme.typography.headlineSmall, color = Palette.text)
            Text("Type this code in the Tuned Remote app:", color = Palette.muted)
            Text(code.toCharArray().joinToString("  "), color = Palette.accent, fontSize = 64.sp, fontWeight = FontWeight.Bold)
            Text("The phone will be able to see your channel list and change channels. It never sees your login.", color = Palette.muted, style = MaterialTheme.typography.bodySmall)
            FocusCard(onClick = onCancel, modifier = Modifier.focusRequester(cancel)) { Text("Cancel") }
        }
    }
}
