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
        val text = assistantInlineText(parseInlineMarkdown("genuinely **bold** and `file.kt`."), Color.Gray,
            existingFiles = setOf("file.kt"))
        assertEquals("genuinely bold and file.kt.", text.text)
        assertEquals(listOf("bold", "file.kt"), text.spanStyles.map { text.text.substring(it.start, it.end) })
        assertEquals(FontWeight.SemiBold, text.spanStyles[0].item.fontWeight)
        assertEquals(FontFamily.Monospace, text.spanStyles[1].item.fontFamily)
    }

    @Test fun onlyResolvedInlineFilesGetLinksAndTheSameExactPathOpens() {
        val source = "Read `./docs/notes.md` or `missing.md` and run `npm test`."
        val paths = fileReferencePaths(parseAssistantMarkdown(source))
        assertTrue("docs/notes.md" in paths)
        assertTrue("missing.md" in paths)
        var opened: String? = null
        val text = assistantInlineText(parseInlineMarkdown(source), Color.Gray, setOf("docs/notes.md")) { opened = it }
        val range = text.getLinkAnnotations(0, text.length).single()
        assertEquals("./docs/notes.md", text.text.substring(range.start, range.end))
        val link = range.item as androidx.compose.ui.text.LinkAnnotation.Clickable
        link.linkInteractionListener!!.onClick(link)
        assertEquals("docs/notes.md", opened)
    }

    @Test fun ghostFilesHaveNeitherCodeHighlightNorLinkStyle() {
        val text = assistantInlineText(parseInlineMarkdown("See `ghost-file.md`"), Color.Gray,
            existingFiles = setOf("real.md"), linkColor = Color.Blue)
        assertEquals("See ghost-file.md", text.text)
        assertTrue(text.spanStyles.isEmpty())
        assertTrue(text.getLinkAnnotations(0, text.length).isEmpty())
    }

    @Test fun linksUseProjectRelativeFilesAndLeaveOtherReferencesInert() {
        val source = "See `README`, `docs/with spaces.md`, `../other.md`, `/tmp/test.md`, `grants/Docs/file.md`."
        assertEquals(setOf("README", "docs/with spaces.md"), fileReferencePaths(parseAssistantMarkdown(source)))
        assertTrue(fileReferencePaths(parseAssistantMarkdown("```\n`fenced.txt`\n```")).isEmpty())
    }
}
