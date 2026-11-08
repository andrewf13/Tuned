package com.andrewf13.tuned.ui

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Text

/** Ask for focus, retrying for a few frames while the target is still being laid out or scrolled in. */
suspend fun FocusRequester.requestWhenReady() {
    repeat(12) {
        withFrameNanos { }
        if (runCatching { requestFocus() }.isSuccess) return
    }
}

/**
 * A text box that behaves on a TV remote. At rest it's an ordinary button, so the d-pad
 * moves past it freely. OK opens the keyboard; the keyboard's search key, Back, Up or Down
 * close it again and hand focus back to the button.
 */
@Composable
fun SearchBox(
    query: String,
    onQuery: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    buttonFocus: FocusRequester = remember { FocusRequester() },
    secret: Boolean = false,
) {
    var editing by remember { mutableStateOf(false) }
    var returnFocus by remember { mutableStateOf(false) }
    val fieldFocus = remember { FocusRequester() }

    fun finish() { editing = false; returnFocus = true }

    LaunchedEffect(editing) { if (editing) fieldFocus.requestWhenReady() }
    LaunchedEffect(returnFocus) { if (returnFocus) { buttonFocus.requestWhenReady(); returnFocus = false } }
    BackHandler(enabled = editing) { finish() }

    if (editing) {
        var hadFocus by remember { mutableStateOf(false) }
        OutlinedTextField(
            value = query, onValueChange = onQuery, singleLine = true,
            label = { androidx.compose.material3.Text(placeholder) },
            keyboardOptions = if (secret) KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done)
            else KeyboardOptions(imeAction = ImeAction.Search),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardActions = KeyboardActions(onSearch = { finish() }, onDone = { finish() }),
            modifier = modifier
                .focusRequester(fieldFocus)
                .onFocusChanged {
                    if (it.isFocused) hadFocus = true
                    else if (hadFocus) editing = false
                }
                .onPreviewKeyEvent { ev ->
                    val k = ev.nativeKeyEvent
                    val escape = k.keyCode == KeyEvent.KEYCODE_DPAD_UP || k.keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
                        k.keyCode == KeyEvent.KEYCODE_BACK
                    if (escape && k.action == KeyEvent.ACTION_DOWN) { finish(); true } else false
                },
        )
    } else {
        FocusCard(onClick = { editing = true }, modifier = modifier.focusRequester(buttonFocus), selected = true) {
            Text(if (secret) "🔑" else "⌕")
            Text(
                if (query.isBlank()) "$placeholder  (press OK to type)" else if (secret) "•".repeat(query.length) else query,
                color = if (query.isBlank()) mutedColor() else androidx.tv.material3.LocalContentColor.current,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
        }
    }
}
