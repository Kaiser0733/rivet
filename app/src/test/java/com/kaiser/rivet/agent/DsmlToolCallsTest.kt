package com.kaiser.rivet.agent

import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class DsmlToolCallsTest {
    private val tools = listOf("list_directory", "git_status").map { AgentToolDefinition(it, "", buildJsonObject {}) }

    @Test fun leadingCallsNeverStreamAsTextAndBecomeTypedCalls() {
        val wire = """
            < | DSML | calls>
            < | DSML | invoke name="list_files">
            < | DSML | parameter name="path" string="true">src</ | DSML | parameter>
            </ | DSML | invoke>
            < | DSML | invoke name="git_status"></ | DSML | invoke>
            </ | DSML | calls>
        """.trimIndent()
        for (width in 1..wire.length) {
            val visible = StringBuilder()
            val adapter = DsmlToolCalls { visible.append(it) }
            wire.chunked(width).forEach(adapter::delta)
            val response = adapter.finish(AgentResponse(text = wire), tools)
            assertEquals("", visible.toString())
            assertEquals("", response.text)
            assertEquals(listOf("list_directory", "git_status"), response.toolCalls.map { it.name })
            assertEquals("""{"path":"src"}""", response.toolCalls.first().arguments)
        }
    }

    @Test fun normalSyntaxAndNativeCallsArePreserved() {
        val native = AgentToolCall("id", "git_status", "{}")
        val visible = StringBuilder()
        val adapter = DsmlToolCalls { visible.append(it) }
        listOf("1 <", " 2 and 3 > 2", " <").forEach(adapter::delta)
        val plain = "1 < 2 and 3 > 2 <"
        assertEquals(AgentResponse(plain, listOf(native)), adapter.finish(AgentResponse(plain, listOf(native)), tools))
        assertEquals(plain, visible.toString())
    }

    @Test fun starterOpeningExecutesBothCallsWhenTheEmptyInvocationHasNoClosingTag() {
        val opening = "I'll start by looking at the workspace.\n"
        val wire = opening + """
            < | DSML | calls>
            < | DSML | invoke name="run_command">
            < | DSML | parameter name="command" string="true">ls -la</ | DSML | parameter>
            </ | DSML | invoke>
            < | DSML | invoke name="git_status">
            </ | DSML | calls>
        """.trimIndent()
        val visible = StringBuilder()
        val adapter = DsmlToolCalls { visible.append(it) }
        wire.chunked(1).forEach(adapter::delta)
        val response = adapter.finish(AgentResponse(text = wire),
            tools + AgentToolDefinition("run_command", "", buildJsonObject {}))

        assertEquals(opening, visible.toString())
        assertEquals(opening, response.text)
        assertEquals(listOf("run_command", "git_status"), response.toolCalls.map { it.name })
        assertEquals(listOf("""{"command":"ls -la"}""", "{}"), response.toolCalls.map { it.arguments })
    }
}
