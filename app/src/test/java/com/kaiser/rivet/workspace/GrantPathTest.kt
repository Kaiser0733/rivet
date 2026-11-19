package com.kaiser.rivet.workspace

import org.junit.Assert.*
import org.junit.Test

class GrantPathTest {
    @Test fun routesProjectGrantRootAndInbox() {
        assertEquals(GrantPath("Downloads", WorkspacePath.parse("x.txt")), GrantPath.parse("grants/Downloads/x.txt"))
        assertEquals(GrantPath("Downloads", WorkspacePath.ROOT), GrantPath.parse("grants/Downloads"))
        assertEquals(GrantPath(null, WorkspacePath.parse("src/a.kt")), GrantPath.parse("src/a.kt"))
        val inbox = GrantPath.parse("inbox/shot.png")
        assertEquals("inbox", inbox.grantId)
        assertEquals("shot.png", inbox.path.value)
        assertTrue(inbox.readOnly)
    }

    @Test fun rejectsTraversalAndMalformedTargets() {
        listOf("grants", "grants//x", "grants/..", "grants/Downloads/../secret", "../x",
            "grants/Downloads//x", "inbox/../x").forEach {
            assertThrows(it, WorkspaceFailure::class.java) { GrantPath.parse(it) }
        }
    }
}
