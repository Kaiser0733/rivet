package com.kaiser.rivet.ui.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kaiser.rivet.browser.BrowserSession
import com.kaiser.rivet.browser.BrowserUiState
import kotlinx.coroutines.delay

@Composable
internal fun LiveBrowserCard(browser: BrowserSession, state: BrowserUiState, working: Boolean) {
    var frame by remember(state.activeWindowId, state.url, state.pageReady) {
        mutableStateOf<ImageBitmap?>(null)
    }
    LaunchedEffect(browser, state.activeWindowId, state.url, state.pageReady) {
        if (!state.pageReady) return@LaunchedEffect
        while (true) {
            frame = browser.chatPreviewFrame()?.asImageBitmap()
            delay(750)
        }
    }
    Surface(onClick = browser::watch, modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (working) "Browsing…" else if (state.error != null) "Browser paused" else "Browser",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary)
            Text(state.title.ifBlank { state.url }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall)
            frame?.let {
                Image(it, contentDescription = "Live page preview",
                    modifier = Modifier.fillMaxWidth().heightIn(max = 176.dp),
                    contentScale = ContentScale.Fit)
            }
        }
    }
}
