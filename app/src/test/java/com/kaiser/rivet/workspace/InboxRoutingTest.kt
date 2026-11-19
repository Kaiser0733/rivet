package com.kaiser.rivet.workspace

import com.kaiser.rivet.agent.AgentWorkspaceFailure
import com.kaiser.rivet.agent.RoutedAgentWorkspace
import com.kaiser.rivet.subagent.BriefParse
import com.kaiser.rivet.subagent.FileScopeRegistry
import com.kaiser.rivet.subagent.WorkerBrief
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class InboxRoutingTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun readsAndPagesOnlyTheCurrentSessionsInbox() = runBlocking {
        val inbox = temporary.newFolder("current")
        inbox.resolve("shared.txt").writeText("hello 🌿")
        temporary.newFolder("other").resolve("private.txt").writeText("other session")
        val router = RoutedAgentWorkspace(null, { _, _ -> error("Unexpected grant access") }, inbox)
        assertEquals(listOf("inbox/shared.txt"), router.list("inbox").map { it.path })
        val region = router.readRegion("inbox/shared.txt", 0, 8)
        assertEquals("hello ", region.text)
        assertEquals(6L, region.end)
        assertEquals("🌿", router.readRegion("inbox/shared.txt", region.end, 8).text)
        assertEquals("missing", (runCatching { router.read("inbox/private.txt") }.exceptionOrNull() as AgentWorkspaceFailure).code)
        assertEquals("inbox_read_only",
            (runCatching { router.createFile("inbox/new") }.exceptionOrNull() as AgentWorkspaceFailure).code)
    }

    @Test fun workerBriefRejectsAnExternalScopeBeforeRegistration() {
        val brief = WorkerBrief.parse("""{"job":"review","brief":"read a file","write_scope":["grants/Downloads"]}""")
        assertTrue(brief is BriefParse.Invalid)
    }

    @Test fun workerScopeCannotAuthorizeGrantWrites() {
        val scopes = FileScopeRegistry()
        val path = WorkspacePath.parse("grants/Downloads/file.txt")
        scopes.register("worker", "feature", listOf(WorkspacePath.ROOT))
        assertEquals(FileScopeRegistry.OUTSIDE_FILE_SCOPE, scopes.checkWrite("worker", listOf(path)))
    }
}
