package com.kaiser.rivet.workspace

import org.junit.Assert.*
import org.junit.Test

class WorkspacePathTest {
    @Test fun validPathsHaveOneCanonicalRepresentation() {
        val path = WorkspacePath.parse("src/main.kt")
        assertEquals("src/main.kt", path.value)
        assertEquals("src", path.parent().value)
        assertEquals("main.kt", path.name)
        assertEquals(path, WorkspacePath.ROOT.child("src").child("main.kt"))
        assertTrue(WorkspacePath.parse("").isRoot)
    }

    @Test fun traversalAbsoluteAndMalformedPathsAreRejected() {
        listOf("..", "../x", "../../x", "/storage/foo", "/absolute/path", "a/../b",
            "a//b", "a/", "./a", "a/./b", "a\\b", "a\u0000b", "a\nb", "a\u007fb").forEach {
            assertThrows(it, WorkspaceFailure::class.java) { WorkspacePath.parse(it) }
        }
    }

    @Test fun simpleNamesCannotContainSeparatorsOrDotSegments() {
        listOf("", ".", "..", "a/b", "a\\b", "\r").forEach {
            assertThrows(WorkspaceFailure::class.java) { WorkspacePath.ROOT.child(it) }
        }
        assertEquals("hello world.kt", WorkspacePath.ROOT.child("hello world.kt").value)
    }

    @Test fun descendantsAreComponentAware() {
        val parent = WorkspacePath.parse("src")
        assertTrue(WorkspacePath.parse("src/a/b").isWithin(parent))
        assertFalse(WorkspacePath.parse("src2/a").isWithin(parent))
    }
}
