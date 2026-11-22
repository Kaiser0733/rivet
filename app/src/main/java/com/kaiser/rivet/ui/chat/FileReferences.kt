package com.kaiser.rivet.ui.chat

import androidx.compose.runtime.staticCompositionLocalOf
import com.kaiser.rivet.agent.presentablePath

internal class AssistantFileLinks(
    val resolve: suspend (Set<String>) -> Set<String>,
    val open: suspend (String) -> Boolean,
)

internal val LocalAssistantFileLinks = staticCompositionLocalOf<AssistantFileLinks?> { null }

internal fun inlineFilePath(run: InlineRun): String? {
    if (!run.code || displaySafeText(run.text) != run.text) return null
    // A path with whitespace needs an explicit directory or file extension to be a reference.
    if (run.text.any(Char::isWhitespace) && '/' !in run.text &&
        !run.text.substringAfterLast('.').matches(Regex("[A-Za-z0-9]+"))) return null
    return runCatching { presentablePath(run.text.removePrefix("./")).value }.getOrNull()
}

internal fun fileReferencePaths(blocks: List<ChatMarkdownBlock>): Set<String> = buildSet {
    fun collect(text: String) {
        parseInlineMarkdown(text).mapNotNullTo(this, ::inlineFilePath)
    }
    for (block in blocks) {
        when (block) {
            is ChatMarkdownBlock.Paragraph -> collect(block.text)
            is ChatMarkdownBlock.Heading -> collect(block.text)
            is ChatMarkdownBlock.Quote -> collect(block.text)
            is ChatMarkdownBlock.ListItems -> block.items.forEach(::collect)
            else -> Unit
        }
    }
}
