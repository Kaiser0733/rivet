package com.kaiser.rivet.ui.chat

import android.util.Size
import androidx.compose.runtime.staticCompositionLocalOf
import com.kaiser.rivet.agent.presentablePath
import com.kaiser.rivet.workspace.FilePreviewResult

internal class AssistantFileLinks(
    val identity: String?,
    val resolve: suspend (Set<String>) -> Set<String>,
    val open: suspend (String) -> Boolean,
    val load: suspend (String, suspend (Size) -> Unit) -> FilePreviewResult,
)

internal val LocalAssistantFileLinks = staticCompositionLocalOf<AssistantFileLinks?> { null }

internal fun inlineFilePath(run: InlineRun): String? {
    if (!run.code || displaySafeText(run.text) != run.text) return null
    // A path with whitespace needs an explicit directory or file extension to be a reference.
    if (run.text.any(Char::isWhitespace) && '/' !in run.text &&
        !run.text.substringAfterLast('.').matches(Regex("[A-Za-z0-9]+"))) return null
    return runCatching { presentablePath(run.text.removePrefix("./")).value }.getOrNull()
}

private val bareFilePath = Regex(
    """(?<![\p{L}\p{N}_./:@~%+#-])(?:\./)?(?:[\p{L}\p{N}_-][\p{L}\p{N}_.-]*/)*(?:[\p{L}\p{N}_-][\p{L}\p{N}_.-]*\.[\p{L}\p{N}]+|README|LICENSE|Makefile|Dockerfile|Gemfile)(?![\p{L}\p{N}_/:@%+#-]|\.[\p{L}\p{N}])""",
)

internal fun fileReferenceRuns(run: InlineRun): List<Pair<InlineRun, String?>> {
    if (run.code) return listOf(run to inlineFilePath(run))
    val matches = bareFilePath.findAll(run.text)
    return buildList {
        var start = 0
        for (match in matches) {
            val candidate = match.value
            val path = runCatching { presentablePath(candidate.removePrefix("./")).value }.getOrNull()
            if (path == null) continue
            if (start < match.range.first) add(run.copy(text = run.text.substring(start, match.range.first)) to null)
            add(run.copy(text = candidate) to path)
            start = match.range.last + 1
        }
        if (start < run.text.length) add(run.copy(text = run.text.substring(start)) to null)
    }
}

internal fun fileReferencePaths(blocks: List<ChatMarkdownBlock>): Set<String> = buildSet {
    fun collect(text: String) {
        parseInlineMarkdown(text).forEach { run ->
            fileReferenceRuns(run).mapNotNullTo(this) { (_, path) -> path }
        }
    }
    for (block in blocks) {
        when (block) {
            is ChatMarkdownBlock.Paragraph -> collect(block.text)
            is ChatMarkdownBlock.Image -> projectImagePath(block.source)?.let(::add)
            is ChatMarkdownBlock.Heading -> collect(block.text)
            is ChatMarkdownBlock.Quote -> collect(block.text)
            is ChatMarkdownBlock.ListItems -> block.items.forEach(::collect)
            else -> Unit
        }
    }
}

internal fun projectImagePath(source: String): String? {
    if (displaySafeText(source) != source || ':' in source) return null
    return runCatching { presentablePath(source.removePrefix("./")).value }.getOrNull()
}
