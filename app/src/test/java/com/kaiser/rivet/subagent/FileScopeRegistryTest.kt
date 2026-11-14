package com.kaiser.rivet.subagent

import com.kaiser.rivet.agent.AgentToolCall
import com.kaiser.rivet.workspace.WorkspacePath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileScopeRegistryTest {
    private fun path(value: String) = WorkspacePath.parse(value)

    @Test fun prefixOverlapIsComponentAwareInBothDirections() {
        assertTrue(FileScopeRegistry.overlaps(listOf(path("src")), listOf(path("src/main.kt"))))
        assertTrue(FileScopeRegistry.overlaps(listOf(path("src/main.kt")), listOf(path("src"))))
        assertFalse(FileScopeRegistry.overlaps(listOf(path("src")), listOf(path("src2/main.kt"))))
        assertFalse(FileScopeRegistry.overlaps(listOf(path("src/a")), listOf(path("src/b"))))
    }

    @Test fun workerCannotWriteOutsideItsDeclaredScope() {
        val registry = FileScopeRegistry()
        registry.register("w1", "fix auth", listOf(path("src/auth")))
        assertNull(registry.checkWrite("w1", listOf(path("src/auth/login.kt"))))
        assertEquals(FileScopeRegistry.OUTSIDE_FILE_SCOPE,
            registry.checkWrite("w1", listOf(path("src/ui/settings.kt"))))
    }

    @Test fun emptyScopeIsAReadOnlyWorker() {
        val registry = FileScopeRegistry()
        registry.register("w1", "research", emptyList())
        assertEquals(FileScopeRegistry.OUTSIDE_FILE_SCOPE,
            registry.checkWrite("w1", listOf(path("README.md"))))
        assertNull(registry.checkWrite("w1", emptyList()))
    }

    @Test fun coordinatorIsBlockedByALiveWorkerScope() {
        val registry = FileScopeRegistry()
        registry.register("w1", "fix auth", listOf(path("src/auth")))
        assertEquals("owned_by_worker:fix auth",
            registry.checkWrite(FileScopeRegistry.COORDINATOR, listOf(path("src/auth/login.kt"))))
        assertNull(registry.checkWrite(FileScopeRegistry.COORDINATOR, listOf(path("src/ui/settings.kt"))))
    }

    @Test fun aParentDirectoryWriteOverlapsAWorkerFile() {
        val registry = FileScopeRegistry()
        registry.register("w1", "move one file", listOf(path("src/auth/login.kt")))
        assertEquals("owned_by_worker:move one file",
            registry.checkWrite(FileScopeRegistry.COORDINATOR, listOf(path("src/auth"))))
    }

    @Test fun moveDestinationIsCheckedWithTheSource() {
        val registry = FileScopeRegistry()
        registry.register("w1", "move auth", listOf(path("src/auth")))
        val inside = AgentToolCall("1", "move_path",
            "{\"path\":\"src/auth/a.kt\",\"destination\":\"src/auth/b.kt\"}")
        assertNull(registry.checkWrite("w1", inside))
        val escaping = AgentToolCall("2", "move_path",
            "{\"path\":\"src/auth/a.kt\",\"destination\":\"src/ui/a.kt\"}")
        assertEquals(FileScopeRegistry.OUTSIDE_FILE_SCOPE,
            registry.checkWrite("w1", escaping))
    }

    @Test fun releaseClearsTheCoordinatorOwnership() {
        val registry = FileScopeRegistry()
        registry.register("w1", "one", listOf(path("src/one")))
        assertEquals("owned_by_worker:one",
            registry.checkWrite(FileScopeRegistry.COORDINATOR, listOf(path("src/one/a.kt"))))
        registry.release("w1")
        assertNull(registry.checkWrite(FileScopeRegistry.COORDINATOR, listOf(path("src/one/a.kt"))))
        assertTrue(registry.liveScopes().none { it.writer == "w1" })
    }

    @Test fun moveToRootAndRenameCheckDestinationPath() {
        val registry = FileScopeRegistry()
        registry.register("w1", "edit single file", listOf(path("src/auth/a.kt")))
        val moveToRoot = AgentToolCall("1", "move_path",
            "{\"path\":\"src/auth/a.kt\",\"destination\":\"\"}")
        assertEquals(FileScopeRegistry.OUTSIDE_FILE_SCOPE, registry.checkWrite("w1", moveToRoot))

        val renameSibling = AgentToolCall("2", "rename_path",
            "{\"path\":\"src/auth/a.kt\",\"new_name\":\"b.kt\"}")
        assertEquals(FileScopeRegistry.OUTSIDE_FILE_SCOPE, registry.checkWrite("w1", renameSibling))
    }
}
