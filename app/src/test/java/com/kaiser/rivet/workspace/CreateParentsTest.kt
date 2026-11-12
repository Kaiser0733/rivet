package com.kaiser.rivet.workspace

import org.junit.Assert.assertEquals
import org.junit.Test

class CreateParentsTest {
    @Test fun missingParentsAreCreatedShallowestFirst() {
        val path = WorkspacePath.parse("a/b/c/file.txt")
        val existing = setOf("a")
        assertEquals(listOf("a/b", "a/b/c").map(WorkspacePath::parse),
            CreateParents.missing(path) { it.value in existing })
    }

    @Test fun aRootFileNeedsNoParents() {
        assertEquals(emptyList<WorkspacePath>(),
            CreateParents.missing(WorkspacePath.parse("file.txt")) { false })
    }
}
