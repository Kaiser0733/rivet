package com.kaiser.rivet.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type

internal fun Modifier.dismissOnEscape(enabled: Boolean = true, dismiss: () -> Unit): Modifier =
    if (!enabled) this else onKeyEvent { event ->
        if (event.key != Key.Escape) false
        else {
            if (event.type == KeyEventType.KeyUp) dismiss()
            true
        }
    }
