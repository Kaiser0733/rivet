package com.kaiser.rivet.ui.provider

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelCountTextTest {
    @Test fun matchingModelCopyUsesSingularOnlyForOneMatch() {
        assertEquals("0 matching models. Showing 0.", matchingModelCount(0, 0))
        assertEquals("1 matching model. Showing 1.", matchingModelCount(1, 1))
        assertEquals("2 matching models. Showing 2.", matchingModelCount(2, 2))
        assertEquals("460 matching models. Showing 30.", matchingModelCount(460, 30))
    }
}
