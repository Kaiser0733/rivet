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
}
