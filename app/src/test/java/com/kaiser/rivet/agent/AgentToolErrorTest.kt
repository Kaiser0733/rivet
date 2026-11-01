package com.kaiser.rivet.agent

import com.kaiser.rivet.workspace.WorkspaceFailure
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolErrorTest {

    private fun requiredAction(json: String): String? =
        Json.parseToJsonElement(json).jsonObject["required_action"]?.jsonPrimitive?.content

    private fun failure(code: String) =
        AgentToolResult("call", "apply_patch", AgentToolError.content(code), error = true)

    @Test fun everyWorkspaceFailureReasonReachesTheModelWithAnExplanation() {
        WorkspaceFailure.Reason.entries.forEach { reason ->
            val code = reason.name.lowercase()
            assertEquals(code, requiredAction(AgentToolError.content(code)), reason.message)
            assertNotNull(code, requiredAction(AgentToolError.noProgress(code)))
        }
    }

    @Test fun uneditableFileCodesAreDeterministicSoTheRunCannotLoop() {
        listOf("patch", "conflict", "too_large", "missing", "binary", "limit",
            "duplicate", "not_file", "not_directory", "root", "permission").forEach { code ->
            assertEquals(code, code, AgentToolError.deterministicCode(failure(code)))
        }
    }

    @Test fun codesARetryCanClearAreExplainedButNotDeterministic() {
        listOf("provider", "write").forEach { code ->
            assertNotNull(code, requiredAction(AgentToolError.content(code)))
            assertNull(code, AgentToolError.deterministicCode(failure(code)))
        }
    }

    @Test fun unknownCodeStaysABareCodeWithoutInventingAdvice() {
        val json = AgentToolError.content("something_new")
        assertEquals("something_new",
            Json.parseToJsonElement(json).jsonObject["error"]?.jsonPrimitive?.content)
        assertNull(requiredAction(json))
    }

    @Test fun retryableIsOnlyClaimedWhenThereIsAnAction() {
        assertTrue(AgentToolError.content("patch").contains("\"retryable\":false"))
        assertTrue(!AgentToolError.content("something_new").contains("retryable"))
    }
}
