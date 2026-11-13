package com.kaiser.rivet.local

import org.junit.Assert.assertEquals
import org.junit.Test

class LlamaBridgeTest {
    @Test fun closeClearsNativeHandleSoDoubleFreeAndLateUseArePrevented() {
        val model = LlamaModel(0x1234ABCDL)
        model.close()
        assertEquals(0L, model.handle)
        model.close()
        assertEquals(0L, model.handle)
    }
}
