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
        assertEquals("", parsed.text)
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
        assertTrue(grammar.contains("\"\\\"read_file\\\"\""))
        assertTrue(grammar.contains("\"\\\"run_command\\\"\""))
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
    fun argumentsCarriedAsAJsonStringAreUnpacked() {
        val parsed = LocalToolProtocol.parse(
            """{"name":"write_file","arguments":"{\"path\":\"local-test.txt\",\"content\":\"hello\",\"expected_sha256\":\"${"a".repeat(64)}\"}"}""",
            setOf("write_file"))
        val call = parsed.calls.single()
        assertEquals("write_file", call.name)
        assertTrue(call.arguments.contains("\"path\":\"local-test.txt\""))
        assertTrue(call.arguments.contains("\"content\":\"hello\""))
    }

    @Test
    fun flatArgumentKeysBecomeTheArgumentsObject() {
        val parsed = LocalToolProtocol.parse(
            """{"name":"create_file","path":"local-test.txt","content":"hello"}""",
            setOf("create_file"))
        val call = parsed.calls.single()
        assertEquals("{\"path\":\"local-test.txt\",\"content\":\"hello\"}", call.arguments)
    }

    @Test
    fun anUnclosedToolCallTagStillParsesItsJson() {
        val parsed = LocalToolProtocol.parse(
            """<tool_call>{"name":"read_file","arguments":{"path":"src/Main.kt"}}""",
            setOf("read_file"))
        val call = parsed.calls.single()
        assertEquals("read_file", call.name)
        assertEquals("{\"path\":\"src/Main.kt\"}", call.arguments)
    }


    @Test
    fun llamasPythonTagAndHermesFencesAllReachTheSameArguments() {
        for (output in listOf(
            """<|python_tag|>{"name":"write_file","arguments":{"path":"a.txt","content":"x"}}<|eom_id|>""",
            """<|tool_call|>{"name":"write_file","parameters":{"path":"a.txt","content":"x"}}<|eot_id|>""",
            """[TOOL_CALLS] {"name":"write_file","arguments":{"path":"a.txt","content":"x"}}""",
            """Here is the call:
```json
{"name":"write_file","arguments":{"path":"a.txt","content":"x"}}
```
That is the call.""",
        )) {
            val parsed = LocalToolProtocol.parse(output, setOf("write_file"))
            assertEquals(output, 1, parsed.calls.size)
            assertEquals("write_file", parsed.calls.single().name)
            assertTrue(parsed.calls.single().arguments.contains("\"path\":\"a.txt\""))
        }
    }

    @Test
    fun malformedJsonIsNotAToolCall() {
        val parsed = LocalToolProtocol.parse(
            """<tool_call>{"name":"write_file","arguments":{"path":</tool_call>""",
            setOf("write_file"))
        assertTrue(parsed.calls.isEmpty())
        assertTrue(parsed.text.contains("tool_call") || parsed.text.contains("write_file"))
    }

    @Test
    fun aBrokenObjectBeforeAValidCallDoesNotHideIt() {
        val parsed = LocalToolProtocol.parse(
            """oops {not json
{"name":"read_file","arguments":{"path":"a"}}""",
            setOf("read_file"))
        assertEquals(1, parsed.calls.size)
        assertEquals("read_file", parsed.calls.single().name)
    }

    @Test
    fun aJsonArrayOfCallsIsParsedWhenTheModelUsesThatShape() {
        val parsed = LocalToolProtocol.parse(
            """[{"name":"read_file","arguments":{"path":"a"}},{"name":"read_file","arguments":{"path":"b"}}]""",
            setOf("read_file"))
        assertEquals(2, parsed.calls.size)
        assertTrue(parsed.calls.all { it.name == "read_file" })
    }
    @Test
    fun everySplitOfAToolTagStillParsesWhenTheChunksAreJoined() {
        val source = """<tool_call>{"name":"read_file","arguments":{"path":"a"}}</tool_call>"""
        for (cut in 1 until source.length) {
            val parsed = LocalToolProtocol.parse(source.substring(0, cut) + source.substring(cut),
                setOf("read_file"))
            assertEquals("cut=$cut", 1, parsed.calls.size)
        }
    }

    @Test
    fun plainAnswersStayPlain() {
        val parsed = LocalToolProtocol.parse("Here is the answer.", emptySet())
        assertEquals("Here is the answer.", parsed.text)
        assertTrue(parsed.calls.isEmpty())
    }

    @Test
    fun grammarQuotesToolNamesAsJsonStringsAndRequiresObjectArguments() {
        val gbnf = LocalToolProtocol.grammar(
            listOf(
                com.kaiser.rivet.agent.AgentToolDefinition(
                    "read_file",
                    "Read a file",
                    kotlinx.serialization.json.buildJsonObject {},
                ),
                com.kaiser.rivet.agent.AgentToolDefinition(
                    "write_file",
                    "Write a file",
                    kotlinx.serialization.json.buildJsonObject {},
                ),
            )
        )
        assertTrue(gbnf, gbnf.contains("name ::= \"\\\"read_file\\\"\" | \"\\\"write_file\\\"\""))
        assertTrue(gbnf, gbnf.contains("\"\\\"arguments\\\"\" ws \":\" ws object ws \"}\""))
    }

    @Test
    fun ignoresToolJsonAndAttemptsInsideThinkingBlocks() {
        val raw = """
            <think>I could call {"name":"read_file","arguments":{"path":"a.kt"}} or <tool_call>{"name":"read_file"}</tool_call></think>
            Here is the plain answer.
        """.trimIndent()
        val parsed = LocalToolProtocol.parse(raw, setOf("read_file"))
        assertTrue(parsed.calls.isEmpty())
        assertEquals("Here is the plain answer.", parsed.text)
        assertTrue(!LocalToolProtocol.looksLikeToolAttempt(raw))
    }
}
