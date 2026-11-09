package com.kaiser.rivet.ui.processes

import org.junit.Assert.assertEquals
import org.junit.Test

class RestoreLabelTest {
    @Test fun checkpointRestoreAndRunUndoHaveDistinctPrimaryLabels() {
        assertEquals("Restore", restorePrimaryLabel(undoRun = false))
        assertEquals("Undo this run", restorePrimaryLabel(undoRun = true))
    }
}
