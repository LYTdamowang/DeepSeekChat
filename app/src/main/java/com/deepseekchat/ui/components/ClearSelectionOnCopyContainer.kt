package com.deepseekchat.ui.components

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ClearSelectionOnCopyContainer(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val androidClipboard = remember(context) {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }
    val scope = rememberCoroutineScope()
    var resetKey by remember { mutableStateOf(0) }
    var clearJob by remember { mutableStateOf<Job?>(null) }
    var lastClipSignature by remember(androidClipboard) {
        mutableStateOf(androidClipboard.primaryClipSignature())
    }
    val clearSelectionAfterClipboardChange by rememberUpdatedState {
        clearJob?.cancel()
        clearJob = scope.launch {
            // Let the platform copy action finish before removing selection handles.
            delay(260)
            resetKey += 1
        }
    }

    DisposableEffect(androidClipboard) {
        val listener = ClipboardManager.OnPrimaryClipChangedListener {
            val currentSignature = androidClipboard.primaryClipSignature()
            if (currentSignature != null && currentSignature != lastClipSignature) {
                lastClipSignature = currentSignature
                clearSelectionAfterClipboardChange()
            }
        }
        androidClipboard.addPrimaryClipChangedListener(listener)
        onDispose {
            androidClipboard.removePrimaryClipChangedListener(listener)
            clearJob?.cancel()
        }
    }

    key(resetKey) {
        SelectionContainer {
            content()
        }
    }
}

private fun ClipboardManager.primaryClipSignature(): String? {
    val clip = primaryClip ?: return null
    val itemCount = clip.itemCount
    val firstText = if (itemCount > 0) {
        clip.getItemAt(0).text?.toString().orEmpty()
    } else {
        ""
    }
    return "${clip.description.label}|$itemCount|${firstText.hashCode()}|${firstText.length}"
}
