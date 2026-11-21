package com.kaiser.rivet.ui.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

internal fun assistantInlineText(runs: List<InlineRun>, codeBackground: Color): AnnotatedString =
    buildAnnotatedString {
        for (run in runs) {
            val content = displaySafeText(run.text)
            if (!run.code && !run.bold && !run.italic) {
                append(content)
            } else {
                withStyle(SpanStyle(
                    fontFamily = if (run.code) FontFamily.Monospace else null,
                    fontWeight = if (run.bold) FontWeight.SemiBold else null,
                    fontStyle = if (run.italic) FontStyle.Italic else null,
                    background = if (run.code) codeBackground else Color.Unspecified,
                )) { append(content) }
            }
        }
    }
