package com.kaiser.rivet.ui.browser

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.kaiser.rivet.browser.BrowserDriving
import com.kaiser.rivet.browser.BrowserUiState
import java.io.File

@Composable
fun BrowserWatchCard(state: BrowserUiState, onWatch: () -> Unit, modifier: Modifier = Modifier) {
    val host = remember(state.url) {
        runCatching { java.net.URI(state.url).host.orEmpty() }.getOrDefault("")
    }
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(10.dp),
        tonalElevation = 3.dp,
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            state.visualScreenshot?.let { path ->
                val bitmap = remember(path) {
                    runCatching { BitmapFactory.decodeFile(File(path).absolutePath) }.getOrNull()
                }
                if (bitmap != null) {
                    Image(bitmap = bitmap.asImageBitmap(), contentDescription = null,
                        modifier = Modifier.size(64.dp))
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    if (state.driving == BrowserDriving.User) "You're driving the browser"
                    else "Rivet is browsing",
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(state.title.ifBlank { host.ifBlank { state.url } },
                    style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                if (host.isNotBlank() && host != state.title) {
                    Text(host, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Button(onClick = onWatch) { Text("Watch") }
        }
    }
}
