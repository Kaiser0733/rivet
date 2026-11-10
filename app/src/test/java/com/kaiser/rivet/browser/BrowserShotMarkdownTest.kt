package com.kaiser.rivet.browser

import com.kaiser.rivet.ui.chat.ChatMarkdownBlock
import com.kaiser.rivet.ui.chat.parseAssistantMarkdown
import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserShotMarkdownTest {
    @Test fun aStandaloneBrowserShotIsAnImageBlockNotRawMarkdown() {
        val blocks = parseAssistantMarkdown("![viewport](/data/user/0/com.kaiser.rivet/files/browser-shots/shot-1.webp)")
        assertEquals(1, blocks.size)
        val image = blocks.single() as ChatMarkdownBlock.Image
        assertEquals("viewport", image.alt)
        assertEquals("/data/user/0/com.kaiser.rivet/files/browser-shots/shot-1.webp", image.source)
    }
}
