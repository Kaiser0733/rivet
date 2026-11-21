package com.kaiser.rivet.ui.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantInlineTextTest {
    @Test fun ordinaryWordsHaveNoFontOverridesInTheCompletedReply() {
        val sentence = "This genuinely works. Cafe\u0301 and العربية also keep their shaping."
        val text = assistantInlineText(parseInlineMarkdown(sentence), Color.Transparent)
        assertEquals(sentence, text.text)
        assertTrue(text.spanStyles.isEmpty())
    }

    @Test fun onlyActualMarkupChangesTheFont() {
        val text = assistantInlineText(parseInlineMarkdown("genuinely **bold** and `file.kt`."), Color.Gray)
        assertEquals("genuinely bold and file.kt.", text.text)
        assertEquals(listOf("bold", "file.kt"), text.spanStyles.map { text.text.substring(it.start, it.end) })
        assertEquals(FontWeight.SemiBold, text.spanStyles[0].item.fontWeight)
        assertEquals(FontFamily.Monospace, text.spanStyles[1].item.fontFamily)
    }
}
