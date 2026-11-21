package com.kaiser.rivet.ui.chat

import com.kaiser.rivet.ui.RivetMotion
import com.kaiser.rivet.ui.entryProgress
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kaiser.rivet.R
import com.kaiser.rivet.agent.PresentedFile
import com.kaiser.rivet.workspace.FilePreviewResult
import kotlin.math.roundToInt

internal class PreviewTabs {
    var paths by mutableStateOf<List<String>>(emptyList())
        private set
    var active by mutableStateOf<String?>(null)
        private set
    var fullScreen by mutableStateOf(false)
    var revision by mutableIntStateOf(0)
        private set
    var result by mutableStateOf<FilePreviewResult?>(null)
        private set
    var missing by mutableStateOf<Set<String>>(emptySet())
        private set

    fun open(path: String) {
        if (path !in paths) paths = paths + path
        active = path
        refresh()
    }

    fun close(path: String) {
        val index = paths.indexOf(path)
        paths = paths - path
        if (active == path) {
            active = paths.getOrNull(index.coerceAtMost(paths.lastIndex))
            refresh()
        }
        if (paths.isEmpty()) fullScreen = false
    }

    fun refresh() {
        result = null
        revision++
    }

    fun resolved(path: String, content: FilePreviewResult) {
        if (active != path) return
        result = content
        missing = if (content == FilePreviewResult.Missing) missing + path else missing - path
    }
}

@Composable
internal fun ChatWithPreview(
    tabs: PreviewTabs,
    load: suspend (String) -> FilePreviewResult,
    content: @Composable () -> Unit,
) {
    val active = tabs.active
    LaunchedEffect(active, tabs.revision) {
        if (active != null) tabs.resolved(active, load(active))
    }
    BackHandler(enabled = active != null) {
        if (tabs.fullScreen) tabs.fullScreen = false else tabs.active?.let(tabs::close)
    }
    var divider by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableFloatStateOf(0.54f) }
    val stateHolder = rememberSaveableStateHolder()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val availableWidth = maxWidth
        val split = chatPaneFraction(availableWidth.value, divider)
        val widthPx = with(LocalDensity.current) { availableWidth.toPx() }
        val dividerWidth = 14.dp.coerceAtMost(availableWidth)
        Row(Modifier.fillMaxSize()) {
            if (!tabs.fullScreen || active == null) {
                val chat = if (active == null) Modifier.weight(1f) else Modifier.width((availableWidth - dividerWidth) * split)
                Box(chat.fillMaxHeight()) {
                    stateHolder.SaveableStateProvider("conversation") { content() }
                }
            }
            if (active != null) {
                if (!tabs.fullScreen) {
                    Box(Modifier.width(dividerWidth).fillMaxHeight()
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .semantics {
                            contentDescription = "Resize file preview"
                            progressBarRangeInfo = ProgressBarRangeInfo(split, 0.32f..0.72f)
                            setProgress { fraction -> divider = chatPaneFraction(availableWidth.value, fraction); true }
                        }
                        .pointerInput(widthPx) {
                            detectHorizontalDragGestures { change, delta ->
                                change.consume()
                                if (widthPx > 0f) divider = chatPaneFraction(availableWidth.value, chatPaneFraction(availableWidth.value, divider) + delta / widthPx)
                            }
                        }, contentAlignment = Alignment.Center) {
                        Box(Modifier.width(2.dp).height(28.dp).background(MaterialTheme.colorScheme.onSurfaceVariant))
                    }
                }
                val progress = entryProgress(Unit, animate = !tabs.fullScreen, duration = RivetMotion.PAGE_MS)
                Box(Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
                    Box(Modifier.fillMaxSize().graphicsLayer {
                        translationX = RivetMotion.paneTravel.toPx() * (1f - progress)
                        alpha = 0.65f + 0.35f * progress
                    }) { PreviewPane(tabs, active) }
                }
            }
        }
    }
}

@Composable
private fun PreviewPane(tabs: PreviewTabs, path: String) {
    val content = tabs.result
    val fileName = path.substringAfterLast('/')
    val type = when (content) {
        is FilePreviewResult.Image -> "Image"
        is FilePreviewResult.Text -> if (content.markdown) "Markdown"
            else if (fileTypeLabel(path) == "Code") "Code" else "Text"
        else -> fileTypeLabel(path)
    }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.statusBarsPadding().navigationBarsPadding()) {
            LazyRow(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow),
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                items(tabs.paths, key = { it }) { tab ->
                    Surface(shape = MaterialTheme.shapes.small, color = if (tab == path)
                        MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLow) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(displaySafeText(tab.substringAfterLast('/')),
                                Modifier.widthIn(max = 136.dp).clickable { tabs.open(tab) }.padding(10.dp),
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            IconButton(onClick = { tabs.close(tab) }, modifier = Modifier.size(44.dp)) {
                                Icon(painterResource(R.drawable.ic_close), "Close ${tab.substringAfterLast('/')}", Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(displaySafeText(fileName), style = MaterialTheme.typography.titleSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(type, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = tabs::refresh) {
                    Icon(painterResource(R.drawable.ic_reload), "Refresh file")
                }
                IconButton(onClick = { tabs.fullScreen = !tabs.fullScreen }) {
                    Icon(painterResource(R.drawable.ic_expand),
                        if (tabs.fullScreen) "Return to split view" else "Expand preview")
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            when (content) {
                is FilePreviewResult.Image -> Image(content.bitmap.asImageBitmap(), fileName,
                    contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(10.dp))
                is FilePreviewResult.Text -> SelectionContainer {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
                        if (content.markdown) AssistantMarkdown(content.content, showInlineImages = false)
                        else Text(content.content, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                    }
                }
                is FilePreviewResult.Unavailable -> PreviewNotice(content.reason, content.size, showSize = true)
                FilePreviewResult.Missing -> PreviewNotice("Moved or deleted", null)
                FilePreviewResult.WrongProject -> PreviewNotice("Open this chat's project to preview its files.", null)
                null -> PreviewNotice("Opening file…", null)
            }
        }
    }
}

@Composable
private fun PreviewNotice(message: String, bytes: Long?, showSize: Boolean = false) {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(message, style = MaterialTheme.typography.bodyMedium)
        if (showSize) {
            val label = when {
                bytes == null -> "Size unavailable"
                bytes < 1024L -> "$bytes bytes"
                else -> "${(bytes / 1024.0).roundToInt()} KB"
            }
            Text(label, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun ProjectFileCard(file: PresentedFile, missing: Boolean, onOpen: () -> Unit) {
    val type = fileTypeLabel(file.path)
    val icon = when (type) {
        "Image" -> R.drawable.ic_visibility
        "Markdown" -> R.drawable.ic_memory
        "Code" -> R.drawable.ic_tools
        else -> R.drawable.ic_files
    }
    Surface(onClick = onOpen, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.heightIn(min = 48.dp).padding(10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(painterResource(icon), type, Modifier.size(20.dp))
            Column(Modifier.weight(1f)) {
                Text(displaySafeText(file.path.substringAfterLast('/')),
                    style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (missing || file.description.isNotBlank()) {
                    Text(if (missing) "Moved or deleted" else displaySafeText(file.description),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Icon(painterResource(R.drawable.ic_chevron_right), null, Modifier.size(16.dp))
        }
    }
}

private fun fileTypeLabel(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
    "md", "markdown", "mdx" -> "Markdown"
    "png", "jpg", "jpeg", "webp", "gif", "bmp", "heic", "avif" -> "Image"
    "kt", "kts", "java", "js", "jsx", "ts", "tsx", "json", "xml", "css", "html", "py",
    "rb", "go", "rs", "sh", "bash", "sql", "c", "cpp", "h", "yml", "yaml", "toml", "gradle" -> "Code"
    "pdf" -> "PDF"
    "doc", "docx", "ppt", "pptx", "xls", "xlsx", "odt" -> "Document"
    else -> "File"
}
