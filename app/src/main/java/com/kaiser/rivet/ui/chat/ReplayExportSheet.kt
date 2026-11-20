package com.kaiser.rivet.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kaiser.rivet.replay.GifTooLarge
import com.kaiser.rivet.replay.ReplayChoice
import com.kaiser.rivet.replay.ReplayExporter
import com.kaiser.rivet.replay.ReplayFile
import com.kaiser.rivet.replay.ReplayFramePalette
import com.kaiser.rivet.replay.ReplayStory
import com.kaiser.rivet.replay.ReplayVideoUnavailable
import com.kaiser.rivet.storage.ReplaySettingsStore
import com.kaiser.rivet.ui.RivetThemeMode
import com.kaiser.rivet.ui.rivetColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReplayExportSheet(
    loadStory: suspend () -> ReplayStory?,
    onDismiss: () -> Unit,
) {
    val app = LocalContext.current.applicationContext
    val settings = remember(app) { ReplaySettingsStore(app) }
    val watermark by settings.watermark.collectAsState(initial = null)
    val exporter = remember(app) { ReplayExporter(app) }
    val palette = remember {
        val colors = rivetColors(RivetThemeMode.Dark, dark = true)
        ReplayFramePalette(colors.background.toArgb(), colors.surface.toArgb(),
            colors.onSurface.toArgb(), colors.tertiary.toArgb(), colors.primary.toArgb(),
            colors.outlineVariant.toArgb())
    }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var prepared by remember { mutableStateOf<ReplayFile?>(null) }
    var story by remember { mutableStateOf<ReplayStory?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Export replay", style = MaterialTheme.typography.titleLarge)
            Text(if (watermark == false) "The watermark is off. You can turn it on in Settings."
                else "Includes a small 'built with Rivet' mark. You can turn it off in Settings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            ReplayChoice.entries.forEach { choice ->
                TextButton(enabled = !busy && watermark != null, onClick = {
                    val watermarkEnabled = watermark ?: return@TextButton
                    busy = true
                    feedback = "Making replay…"
                    scope.launch {
                        try {
                            val source = story ?: loadStory()
                            if (source == null) {
                                feedback = "This turn changed. Open the replay again."
                                return@launch
                            }
                            story = source
                            val format = choice.format(prepared?.format)
                            val file = prepared?.takeIf {
                                it.format == format && it.watermark == watermarkEnabled && it.file.isFile
                            } ?: exporter.render(source, palette, format, watermarkEnabled)
                            prepared = file
                            feedback = try {
                                exporter.distribute(file, choice)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                if (choice == ReplayChoice.ShareSheet) "Couldn't open the share sheet."
                                else "Couldn't finish exporting. Try the share sheet."
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: ReplayVideoUnavailable) {
                            feedback = error.message
                        } catch (error: GifTooLarge) {
                            feedback = error.message
                        } catch (_: Exception) {
                            feedback = "Couldn't make this replay. Try again."
                        } finally { busy = false }
                    }
                }) { Text(choice.label) }
            }
            feedback?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}
