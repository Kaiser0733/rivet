package com.kaiser.rivet.workspace

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class WorkspaceSearchTest {
    private fun entry(name: String, directory: Boolean = false, id: String = name) =
        WorkspaceEntry(WorkspacePath.parse(name), id, directory, if (directory) "directory" else "text/plain")

    @Test fun directoriesFirstThenDeterministicNames() {
        val sorted = listOf(entry("z"), entry("B"), entry("a"), entry("Z", true), entry("a", true)).sortedWith(WorkspaceEntry.ORDER)
        assertEquals(listOf("a", "Z", "a", "B", "z"), sorted.map { it.path.value })
    }
    @Test fun searchesNamesAndContentsWithLineContext() = runTest {
        val result = searchWorkspace("needle", WorkspacePath.ROOT, SearchLimits(),
            { _, _ -> listOf(entry("needle.kt"), entry("other.kt")) },
            { _, _ -> "first\nneedle here\nlast".toByteArray() })
        assertEquals(3, result.hits.size)
        assertEquals(2, result.hits.last().line)
        assertEquals("needle here", result.hits.last().context)
    }
    @Test fun fileResultAndByteBudgetsAreEnforced() = runTest {
        var read = 0
        val result = searchWorkspace("hit", WorkspacePath.ROOT,
            SearchLimits(maxFiles = 2, maxBytes = 6, maxResults = 1, perFileBytes = 4),
            { _, _ -> (1..5).map { entry("$it.kt") } },
            { _, cap -> read++; assertTrue(cap <= 4); "hit".toByteArray() })
        assertEquals(1, result.hits.size)
        assertEquals(1, read)
        assertTrue(result.limited)
        assertTrue(result.bytesScanned <= 6)
    }
    @Test fun binaryAndInaccessibleFilesAreSkipped() = runTest {
        val result = searchWorkspace("hit", WorkspacePath.ROOT, SearchLimits(),
            { _, _ -> listOf(entry("a"), entry("b"), entry("c")) },
            { e, _ -> when (e.path.value) {
                "a" -> byteArrayOf(0, 1)
                "b" -> throw WorkspaceFailure(WorkspaceFailure.Reason.PROVIDER)
                else -> "hit".toByteArray()
            } })
        assertEquals(1, result.hits.size)
        assertEquals(2, result.skipped)
    }
    @Test fun cancellationIsNotSwallowed() = runTest {
        try {
            searchWorkspace("x", WorkspacePath.ROOT, SearchLimits(),
                { _, _ -> throw CancellationException("stop") }, { _, _ -> byteArrayOf() })
            fail("Cancellation swallowed")
        } catch (_: CancellationException) { }
    }
    @Test fun directoryAliasesCannotRecurseForever() = runTest {
        var listings = 0
        val result = searchWorkspace("x", WorkspacePath.ROOT, SearchLimits(maxEntries = 5),
            { _, _ -> listings++; listOf(entry("child", true, "same")) }, { _, _ -> byteArrayOf() })
        assertTrue(listings <= 2)
        assertTrue(result.entriesVisited <= 5)
    }
    @Test fun unknownLengthOversizeReadsStayWithinTotalBudget() = runTest {
        var consumed = 0
        val result = searchWorkspace("none", WorkspacePath.ROOT,
            SearchLimits(maxBytes = 5, perFileBytes = 4),
            { _, _ -> listOf(entry("a"), entry("b")) },
            { _, cap -> consumed += cap + 1; throw WorkspaceFailure(WorkspaceFailure.Reason.TOO_LARGE) })
        assertTrue("Consumed $consumed bytes", consumed <= 5)
        assertTrue(result.bytesScanned <= 5)
        assertTrue(result.limited)
    }
    @Test fun epochsRejectStaleResults() {
        val epoch = WorkspaceEpoch()
        val old = epoch.next()
        assertTrue(epoch.isCurrent(old))
        val current = epoch.next()
        assertFalse(epoch.isCurrent(old))
        assertTrue(epoch.isCurrent(current))
    }
}
