package com.kaiser.rivet.ui.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kaiser.rivet.R
import com.kaiser.rivet.browser.BrowserSession
import com.kaiser.rivet.browser.BrowserUiState
import kotlinx.coroutines.delay

@Composable
internal fun LiveBrowserCard(browser: BrowserSession, state: BrowserUiState) {
    val page = state.chatPage ?: return
    val working = state.chatBrowserWorking
    val canPreview = state.url == page.url && state.rendererVisible && state.pageReady &&
        !state.teardownRequested && state.error == null
    val configuration = LocalConfiguration.current
    var frame by remember(page.url, state.activeWindowId, canPreview, configuration.orientation) {
        mutableStateOf<ImageBitmap?>(null)
    }
    LaunchedEffect(browser, page.url, state.activeWindowId, canPreview, working, configuration.orientation) {
        if (!canPreview) return@LaunchedEffect
        var remaining = 3
        while (working || remaining-- > 0) {
            browser.chatPreviewFrame()?.let { frame = it.asImageBitmap() }
            delay(1_000)
        }
    }
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    Surface(
        onClick = browser::openChatBrowser,
        modifier = Modifier.widthIn(max = 540.dp).fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = colors.surfaceContainerLow,
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(44.dp).background(colors.primary.copy(alpha = 0.12f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(painterResource(R.drawable.ic_browser), contentDescription = null, tint = colors.primary)
                }
                Column {
                    Text("Browser", style = typography.titleSmall)
                    Text(if (working) "Working" else "Done", style = typography.bodySmall,
                        color = colors.onSurfaceVariant)
                }
            }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val previewHeight = minOf(maxWidth * (9f / 16f), 290.dp, configuration.screenHeightDp.dp * 0.44f)
                Box(
                    Modifier.fillMaxWidth().height(previewHeight).clip(MaterialTheme.shapes.small)
                        .background(colors.surfaceContainerLowest),
                    contentAlignment = Alignment.Center,
                ) {
                    val image = frame
                    when {
                        image != null -> Image(image, contentDescription = "Browser page preview",
                            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                            alignment = Alignment.TopCenter)
                        working && page.title.isBlank() && page.excerpt.isBlank() ->
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        else -> Column(
                            Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(page.title.ifBlank { page.url }, style = typography.titleSmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (page.excerpt.isNotBlank() && previewHeight >= 90.dp) {
                                Text(page.excerpt, style = typography.bodyMedium, color = colors.onSurfaceVariant,
                                    maxLines = if (previewHeight >= 150.dp) 4 else 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
                color = colors.surfaceContainerHigh,
            ) {
                Box(
                    Modifier.heightIn(min = 46.dp).padding(horizontal = 12.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Open browser", style = typography.titleSmall)
                }
            }
        }
    }
}
