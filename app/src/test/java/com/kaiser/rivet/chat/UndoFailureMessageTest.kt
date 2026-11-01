package com.kaiser.rivet.chat

import com.kaiser.rivet.runtime.CheckpointFailure
import com.kaiser.rivet.runtime.MirrorFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class UndoFailureMessageTest {

    @Test fun aDesyncedUndoExplainsThatBothCopiesMayDiffer() {
        assertEquals(
            "Rivet restored its copy but could not write it back to the project, so the two may differ. Check the files before continuing.",
            undoFailureMessage(CheckpointFailure("undo_desync")))
    }

    @Test fun aDesyncedUndoIsNotConfusedWithAConflict() {
        assertNotEquals(undoFailureMessage(CheckpointFailure("undo_desync")),
            undoFailureMessage(MirrorFailure("conflict")))
        assertNotEquals(undoFailureMessage(CheckpointFailure("undo_desync")),
            undoFailureMessage(CheckpointFailure("undo_conflict")))
    }

    @Test fun unknownFailuresKeepTheGenericLine() {
        assertEquals("Rivet couldn't finish Undo. Check the project before trying again.",
            undoFailureMessage(RuntimeException("boom")))
        assertEquals("Rivet couldn't finish Undo. Check the project before trying again.",
            undoFailureMessage(null))
    }
}
