package com.kaiser.rivet.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserElementRefTest {
    @Test fun elementsCarryShortStableRefs() {
        val refs = BrowserElementRefs.fromJson(
            """[{"label":"Docs","tag":"a"},{"label":"Send","tag":"button"}]""")
        assertEquals("#1", refs[0].token())
        assertEquals("#2", refs[1].token())
        assertEquals("Send", refs[1].label)
    }

    @Test fun handlesLabelsWithBracesEscapedQuotesBackslashesAndWebViewJsonWrapping() {
        val raw = "\"[{\\\"label\\\":\\\"Save {v2} \\\\\\\"now\\\\\\\"\\\\nnext\\\",\\\"tag\\\":\\\"button\\\"},{\\\"label\\\":\\\"Cancel\\\",\\\"tag\\\":\\\"a\\\"}]\""
        val refs = BrowserElementRefs.fromJson(BrowserElementRefs.unwrapJsResult(raw))
        assertEquals(2, refs.size)
        assertEquals("Save {v2} \"now\"\nnext", refs[0].label)
        assertEquals("button", refs[0].tag)
        assertEquals("Cancel", refs[1].label)
        assertEquals("a", refs[1].tag)

        val direct = BrowserElementRefs.fromJson("""[{"label":"Config {a:1} \"ok\"","tag":"button"}]""")
        assertEquals(1, direct.size)
        assertEquals("Config {a:1} \"ok\"", direct[0].label)
    }
}
