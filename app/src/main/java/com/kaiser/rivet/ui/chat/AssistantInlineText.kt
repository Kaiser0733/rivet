package com.kaiser.rivet.ui.chat

import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

internal fun assistantInlineText(
    runs: List<InlineRun>,
    codeBackground: Color,
    existingFiles: Set<String> = emptySet(),
    linkColor: Color = Color.Unspecified,
    open: (String) -> Unit = {},
): AnnotatedString =
    buildAnnotatedString {
        for ((run, candidate) in runs.flatMap(::fileReferenceRuns)) {
            val content = displaySafeText(run.text)
            val path = candidate?.takeIf { it in existingFiles }
            val explicitPath = candidate?.let { '.' in it || '/' in it ||
                it in setOf("README", "LICENSE", "Makefile", "Dockerfile", "Gemfile") } == true
            val showCode = run.code && (!explicitPath || path != null)
            if (path == null && !showCode && !run.bold && !run.italic) {
                append(content)
                continue
            }
            withStyle(SpanStyle(
                fontFamily = if (showCode) FontFamily.Monospace else null,
                fontWeight = if (run.bold) FontWeight.SemiBold else null,
                fontStyle = if (run.italic) FontStyle.Italic else null,
                background = if (showCode) codeBackground else Color.Unspecified,
            )) {
                if (path == null) append(content)
                else withLink(LinkAnnotation.Clickable(path,
                    styles = TextLinkStyles(style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)),
                    linkInteractionListener = { open(path) },
                )) { append(content) }
            }
        }
    }
