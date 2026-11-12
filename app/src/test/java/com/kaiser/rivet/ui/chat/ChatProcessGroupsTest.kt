package com.kaiser.rivet.ui.chat

import com.kaiser.rivet.agent.AgentActivityOperation
import com.kaiser.rivet.agent.ActivityOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatProcessGroupsTest {

    @Test
    fun classifiesToolsIntoFourteenKinds() {
        assertEquals(ProcessActivityKind.Read, ProcessActivityKind.fromToolName("read_file"))
        assertEquals(ProcessActivityKind.Read, ProcessActivityKind.fromToolName("read"))
        assertEquals(ProcessActivityKind.ReadImage, ProcessActivityKind.fromToolName("read_image"))
        assertEquals(ProcessActivityKind.Search, ProcessActivityKind.fromToolName("search_files"))
        assertEquals(ProcessActivityKind.Search, ProcessActivityKind.fromToolName("list_directory"))
        assertEquals(ProcessActivityKind.Search, ProcessActivityKind.fromToolName("git_status"))
        assertEquals(ProcessActivityKind.Search, ProcessActivityKind.fromToolName("git_diff"))
        assertEquals(ProcessActivityKind.Search, ProcessActivityKind.fromToolName("grep"))
        assertEquals(ProcessActivityKind.Search, ProcessActivityKind.fromToolName("glob"))
        assertEquals(ProcessActivityKind.Search, ProcessActivityKind.fromToolName("ast_inspect"))
        assertEquals(ProcessActivityKind.Write, ProcessActivityKind.fromToolName("write_file"))
        assertEquals(ProcessActivityKind.Write, ProcessActivityKind.fromToolName("create_file"))
        assertEquals(ProcessActivityKind.Write, ProcessActivityKind.fromToolName("create_directory"))
        assertEquals(ProcessActivityKind.Write, ProcessActivityKind.fromToolName("download_file"))
        assertEquals(ProcessActivityKind.Edit, ProcessActivityKind.fromToolName("apply_patch"))
        assertEquals(ProcessActivityKind.Edit, ProcessActivityKind.fromToolName("edit"))
        assertEquals(ProcessActivityKind.Edit, ProcessActivityKind.fromToolName("delete_path"))
        assertEquals(ProcessActivityKind.Edit, ProcessActivityKind.fromToolName("rename_path"))
        assertEquals(ProcessActivityKind.Commands, ProcessActivityKind.fromToolName("run_command"))
        assertEquals(ProcessActivityKind.Commands, ProcessActivityKind.fromToolName("bash"))
        assertEquals(ProcessActivityKind.Commands, ProcessActivityKind.fromToolName("terminal_exec"))
        assertEquals(ProcessActivityKind.Code, ProcessActivityKind.fromToolName("run_code"))
        assertEquals(ProcessActivityKind.WebSearch, ProcessActivityKind.fromToolName("web_search"))
        assertEquals(ProcessActivityKind.WebSearch, ProcessActivityKind.fromToolName("browser_search"))
        assertEquals(ProcessActivityKind.WebFetch, ProcessActivityKind.fromToolName("browser_open"))
        assertEquals(ProcessActivityKind.WebFetch, ProcessActivityKind.fromToolName("browser_click"))
        assertEquals(ProcessActivityKind.WebFetch, ProcessActivityKind.fromToolName("browser_shot"))
        assertEquals(ProcessActivityKind.WebFetch, ProcessActivityKind.fromToolName("web_fetch"))
        assertEquals(ProcessActivityKind.Subagents, ProcessActivityKind.fromToolName("subagent"))
        assertEquals(ProcessActivityKind.Subagents, ProcessActivityKind.fromToolName("invoke_subagent"))
        assertEquals(ProcessActivityKind.Subagents, ProcessActivityKind.fromToolName("swarm"))
        assertEquals(ProcessActivityKind.Plan, ProcessActivityKind.fromToolName("todo_write"))
        assertEquals(ProcessActivityKind.Plan, ProcessActivityKind.fromToolName("create_goal"))
        assertEquals(ProcessActivityKind.Plan, ProcessActivityKind.fromToolName("remember"))
        assertEquals(ProcessActivityKind.Questions, ProcessActivityKind.fromToolName("ask_user_question"))
        assertEquals(ProcessActivityKind.Questions, ProcessActivityKind.fromToolName("request_user_input"))
        assertEquals(ProcessActivityKind.Questions, ProcessActivityKind.fromToolName("ask_user"))
        assertEquals(ProcessActivityKind.Tools, ProcessActivityKind.fromToolName("custom_unknown_tool"))
    }

    @Test
    fun dedupesCallIdsAndRanksByCountDescAndFirstAppearance() {
        val items = listOf(
            ProcessCallItem("call-1", "read_file"),
            ProcessCallItem("call-2", "run_command"),
            ProcessCallItem("call-1", "read_file"), // duplicate callId
            ProcessCallItem("call-3", "run_command"),
            ProcessCallItem("call-4", "run_code"),
            ProcessCallItem("call-5", "run_command"),
        )
        val counts = calculateActivityCounts(items)
        assertEquals(3, counts.size)
        // Commands has 3 (call-2, call-3, call-5)
        assertEquals(ProcessActivityKind.Commands, counts[0].kind)
        assertEquals(3, counts[0].count)
        // Read has 1 (call-1) and Code has 1 (call-4). Read appeared at index 0, Code at index 4
        assertEquals(ProcessActivityKind.Read, counts[1].kind)
        assertEquals(1, counts[1].count)
        assertEquals(ProcessActivityKind.Code, counts[2].kind)
        assertEquals(1, counts[2].count)
    }

    @Test
    fun formatsProcessTitleAccordingToDshSpec() {
        assertEquals("Analysis completed", formatProcessTitle(emptyList()))

        val one = listOf(ActivityCount(ProcessActivityKind.Commands, 2, 0))
        assertEquals("Ran commands", formatProcessTitle(one))

        val two = listOf(
            ActivityCount(ProcessActivityKind.Code, 3, 0),
            ActivityCount(ProcessActivityKind.Commands, 2, 1)
        )
        assertEquals("Ran code and ran commands", formatProcessTitle(two))

        val three = listOf(
            ActivityCount(ProcessActivityKind.Read, 5, 0),
            ActivityCount(ProcessActivityKind.Code, 3, 1),
            ActivityCount(ProcessActivityKind.Commands, 2, 2)
        )
        assertEquals("Read files, ran code, ran commands", formatProcessTitle(three))

        val four = listOf(
            ActivityCount(ProcessActivityKind.Read, 5, 0),
            ActivityCount(ProcessActivityKind.Code, 3, 1),
            ActivityCount(ProcessActivityKind.Commands, 2, 2),
            ActivityCount(ProcessActivityKind.Write, 1, 3)
        )
        assertEquals("Read files, ran code, ran commands, etc.", formatProcessTitle(four))
    }

    @Test
    fun extractsLiveToolDetailFromOrderedKeys() {
        val json = """{"command": "git status", "path": "src/main.kt"}"""
        // command is checked before path in LIVE_TOOL_DETAIL_KEYS
        val detail = extractLiveToolDetail("run_command", json)
        assertEquals("git status", detail)

        val jsonPathOnly = """{"path": "src/App.kt"}"""
        assertEquals("src/App.kt", extractLiveToolDetail("read_file", jsonPathOnly))

        val jsonQueries = """{"queries": ["foo", "bar"]}"""
        assertEquals("foo, bar", extractLiveToolDetail("search", jsonQueries))
    }

    @Test
    fun normalizesAndCapsLiveDetailAt160Chars() {
        val longText = "a".repeat(200)
        val capped = normalizeLiveDetail(longText)
        assertEquals(160, capped.length)
        assertTrue(capped.endsWith("…"))
        assertEquals("a".repeat(159) + "…", capped)

        val multiline = "first line\n   second line \t third"
        assertEquals("first line second line third", normalizeLiveDetail(multiline))
    }

    @Test
    fun extractsLiveReasoningDetailFromLastNonEmptyParagraph() {
        val reasoning = """
            Here is the first paragraph with some details.

            **Checking dependencies** and verifying imports.

            
        """.trimIndent()
        val detail = extractLiveReasoningDetail(reasoning)
        assertEquals("Checking dependencies and verifying imports.", detail)
    }

    @Test
    fun formatsLiveLabelWithRunningOrPreparingOrReasoning() {
        val runningSummary = ProcessGroupSummary(
            counts = listOf(ActivityCount(ProcessActivityKind.Commands, 1, 0)),
            running = ProcessActivityKind.Commands,
            runningDetail = "npm test",
            preparing = false
        )
        assertEquals("Running commands · npm test", runningSummary.liveLabel())

        val preparingSummary = ProcessGroupSummary(
            counts = emptyList(),
            running = ProcessActivityKind.Commands,
            runningDetail = "bash",
            preparing = true
        )
        assertEquals("Preparing to run commands · bash", preparingSummary.liveLabel())

        val reasoningSummary = ProcessGroupSummary(
            counts = emptyList(),
            running = null,
            runningDetail = "Analyzing code structure",
            preparing = false
        )
        assertEquals("Analyzing the request · Analyzing code structure", reasoningSummary.liveLabel())
    }

    @Test
    fun formatsRunDurationCompactly() {
        assertEquals("1s", formatRunDuration(200L)) // clamped >= 1000ms
        assertEquals("45s", formatRunDuration(45000L))
        assertEquals("1m5s", formatRunDuration(65000L))
        assertEquals("2m0s", formatRunDuration(120000L))
        assertEquals("1h1m5s", formatRunDuration(3665000L))
    }

    @Test
    fun checksTurnFoldEligibility() {
        val completed = ChatTurnProcessSpec(turnIndex = 0, durationMs = 15000L)
        assertTrue(completed.canFold)
        assertEquals("Completed in 15s", completed.foldLabel())

        val live = ChatTurnProcessSpec(turnIndex = 1, isLive = true)
        assertFalse(live.canFold)

        val stopped = ChatTurnProcessSpec(turnIndex = 2, isStopped = true)
        assertFalse(stopped.canFold)
        assertEquals("Stopped", stopped.foldLabel())

        val error = ChatTurnProcessSpec(turnIndex = 3, isError = true)
        assertFalse(error.canFold)
        assertEquals("Failed", error.foldLabel())

        val interleaved = ChatTurnProcessSpec(turnIndex = 4, hasInterleavedUserInput = true, durationMs = 8000L)
        assertFalse(interleaved.canFold)
    }

    @Test
    fun segmentsTurnItemsIntoTurnsAndTracksFoldState() {
        val items = listOf(
            // Turn 0: User -> Process -> Answer
            TurnItem("user-0", isUser = true, isAnswer = false, isProcess = false),
            TurnItem("proc-0a", isUser = false, isAnswer = false, isProcess = true),
            TurnItem("proc-0b", isUser = false, isAnswer = false, isProcess = true),
            TurnItem("answer-0", isUser = false, isAnswer = true, isProcess = false),
            // Turn 1: User -> Process (Live)
            TurnItem("user-1", isUser = true, isAnswer = false, isProcess = false),
            TurnItem("proc-1", isUser = false, isAnswer = false, isProcess = true),
        )
        val durations = mapOf(0 to 14000L)
        val turns = segmentTurnItems(
            items = items,
            isStreaming = true,
            turnDurationsMs = durations,
            isInterruptedTurn = false,
            hasError = false
        )

        assertEquals(2, turns.size)

        // Turn 0 is completed
        val turn0 = turns[0]
        assertEquals(0, turn0.turnIndex)
        assertEquals("user-0", turn0.userItem)
        assertEquals(listOf("proc-0a", "proc-0b"), turn0.processItems)
        assertEquals("answer-0", turn0.answerItem)
        assertTrue(turn0.spec.canFold)
        assertEquals("Completed in 14s", turn0.spec.foldLabel())

        // Turn 1 is live
        val turn1 = turns[1]
        assertEquals(1, turn1.turnIndex)
        assertEquals("user-1", turn1.userItem)
        assertEquals(listOf("proc-1"), turn1.processItems)
        assertEquals(null, turn1.answerItem)
        assertFalse(turn1.spec.canFold)
    }

    @Test
    fun segmentsTurnItemsHandlesStoppedAndErrorTurns() {
        val items = listOf(
            TurnItem("user-0", isUser = true, isAnswer = false, isProcess = false),
            TurnItem("proc-0", isUser = false, isAnswer = false, isProcess = true),
        )
        val turnsStopped = segmentTurnItems(
            items = items,
            isStreaming = false,
            turnDurationsMs = emptyMap(),
            isInterruptedTurn = true,
            hasError = false
        )
        assertEquals(1, turnsStopped.size)
        assertFalse(turnsStopped[0].spec.canFold)
        assertEquals("Stopped", turnsStopped[0].spec.foldLabel())

        val turnsError = segmentTurnItems(
            items = items,
            isStreaming = false,
            turnDurationsMs = emptyMap(),
            isInterruptedTurn = false,
            hasError = true
        )
        assertEquals(1, turnsError.size)
        assertFalse(turnsError[0].spec.canFold)
        assertEquals("Failed", turnsError[0].spec.foldLabel())
    }
    @Test
    fun keepsProcessAndAnswerOrderInsteadOfGroupingAllProcessFirst() {
        val items = listOf(
            TurnItem("user-0", isUser = true, isAnswer = false, isProcess = false),
            TurnItem("proc-0", isUser = false, isAnswer = false, isProcess = true),
            TurnItem("answer-0", isUser = false, isAnswer = true, isProcess = false),
            TurnItem("proc-1", isUser = false, isAnswer = false, isProcess = true),
            TurnItem("answer-1", isUser = false, isAnswer = true, isProcess = false),
        )
        val turn = segmentTurnItems(items, false, mapOf(0 to 1000L), false, false).single()
        assertEquals(4, turn.parts.size)
        assertTrue(turn.parts[0] is TurnPart.Process)
        assertEquals(listOf("proc-0"), (turn.parts[0] as TurnPart.Process).items)
        assertTrue(turn.parts[1] is TurnPart.Answer)
        assertEquals("answer-0", (turn.parts[1] as TurnPart.Answer).item)
        assertTrue(turn.parts[2] is TurnPart.Process)
        assertEquals(listOf("proc-1"), (turn.parts[2] as TurnPart.Process).items)
        assertTrue(turn.parts[3] is TurnPart.Answer)
        assertEquals("answer-1", (turn.parts[3] as TurnPart.Answer).item)
    }

    @Test
    fun mergesAdjacentProcessItemsIntoOnePreAnswerRun() {
        val items = listOf(
            TurnItem("user-0", isUser = true, isAnswer = false, isProcess = false),
            TurnItem("proc-0", isUser = false, isAnswer = false, isProcess = true),
            TurnItem("proc-1", isUser = false, isAnswer = false, isProcess = true),
            TurnItem("answer-0", isUser = false, isAnswer = true, isProcess = false),
        )
        val turn = segmentTurnItems(items, false, mapOf(0 to 1000L), false, false).single()
        assertEquals(2, turn.parts.size)
        assertEquals(listOf("proc-0", "proc-1"), (turn.parts[0] as TurnPart.Process).items)
        assertTrue(turn.parts[1] is TurnPart.Answer)
    }

    @Test
    fun classifiesOperationsFromTheCarriedToolNameNotTheHumanTitle() {
        val operation = AgentActivityOperation(
            id = "hashed-call",
            title = "Run",
            detail = "gradlew test",
            outcome = ActivityOutcome.Completed,
            toolName = "run_command",
        )
        val item = processCallItems(listOf(operation)).single()
        assertEquals("run_command", item.name)
        assertEquals(ProcessActivityKind.Commands, item.kind)
    }

}
