package com.kaiser.rivet.local

import com.kaiser.rivet.agent.AgentToolDefinition
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalToolProtocolTest {
    private fun catalogueFile(): java.io.File =
        listOf(java.io.File("src/main/assets/local-models.json"),
            java.io.File("app/src/main/assets/local-models.json")).first { it.isFile }

    @Test
    fun parsesQwenTaggedToolCallsAndLeavesTheText() {
        val parsed = LocalToolProtocol.parse("""
            I will inspect it.
            <tool_call>
            {"name":"read_file","arguments":{"path":"src/Main.kt"}}
            </tool_call>
        """.trimIndent(), setOf("read_file"))
        assertEquals("I will inspect it.", parsed.text)
        assertEquals(1, parsed.calls.size)
        assertEquals("read_file", parsed.calls.single().name)
        assertEquals("{\"path\":\"src/Main.kt\"}", parsed.calls.single().arguments)
    }

    @Test
    fun ignoresToolNamesOutsideTheLocalCoreSet() {
        val parsed = LocalToolProtocol.parse(
            "{\"name\":\"delete_everything\",\"arguments\":{}}", setOf("read_file"))
        assertTrue(parsed.calls.isEmpty())
    }

    @Test
    fun grammarNamesOnlyTheProvidedTools() {
        val grammar = LocalToolProtocol.grammar(listOf(
            AgentToolDefinition("read_file", "read", buildJsonObject {}),
            AgentToolDefinition("run_command", "run", buildJsonObject {})))
        assertTrue(grammar.contains("\"read_file\""))
        assertTrue(grammar.contains("\"run_command\""))
        assertTrue(grammar.contains("root ::="))
    }

    @Test
    fun aNoneCapabilityModelIsRefusedInPlainWords() {
        val entry = com.kaiser.rivet.local.ModelCatalogue.parse(
            catalogueFile().readText()).first()
            .copy(toolCalling = LocalToolCalling.None)
        val reason = LocalToolProtocol.agentBlockReason(entry)
        assertTrue(reason != null && reason.contains("can't use tools"))
        assertTrue(LocalToolProtocol.agentBlockReason(entry.copy(toolCalling = LocalToolCalling.Verified)) == null)
    }

    @Test
    fun plainAnswersStayPlain() {
        val parsed = LocalToolProtocol.parse("Here is the answer.", emptySet())
        assertEquals("Here is the answer.", parsed.text)
        assertTrue(parsed.calls.isEmpty())
    }
}
