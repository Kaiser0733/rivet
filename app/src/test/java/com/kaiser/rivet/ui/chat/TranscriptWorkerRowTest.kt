package com.kaiser.rivet.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.kaiser.rivet.agent.ActivityOutcome
import com.kaiser.rivet.agent.AgentActivityGroup
import com.kaiser.rivet.agent.AgentActivityOperation
import com.kaiser.rivet.subagent.WorkerRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TranscriptWorkerRowTest {
    @get:Rule val compose = createComposeRule()

    @Test fun tappingTheOperationOpensTheMatchedWorkerSheet() {
        val worker = WorkerRecord("w-1", "run", "chat", "project", "Inspect docs", "",
            configId = "cloud", model = "m", modelLabel = "Example model")
        val selected = mutableStateOf<String?>(null)
        compose.setContent {
            if (selected.value == worker.id) {
                WorkerScreen(worker, emptyList(), {}, emptyList(), false,
                    onDismiss = {}, onStop = {}, onContinue = {})
            } else {
                CompositionLocalProvider(LocalOpenTranscriptWorker provides { selected.value = it }) {
                    ActivityOperationRow(AgentActivityOperation("call", "Start worker",
                        "Inspect docs\nExample model", ActivityOutcome.Completed,
                        toolName = "spawn_subagent", workerId = worker.id))
                }
            }
        }

        compose.onNodeWithText("Inspect docs", substring = true).performClick()
        compose.onNodeWithText("Subagents").assertIsDisplayed()
        compose.onNodeWithText("Example model · ").assertIsDisplayed()
    }

    @Test fun consecutiveLiveActionsShareOneCollapsedFold() {
        val groups = listOf(
            AgentActivityGroup("one", "", listOf(
                AgentActivityOperation("a", "Read image", "qa-shots/t10.png", ActivityOutcome.Completed,
                    toolName = "read_image"),
            )),
            AgentActivityGroup("two", "", listOf(
                AgentActivityOperation("b", "Load skill", "review", ActivityOutcome.Failed,
                    outcomeDetail = "Could not load skill\nPrivate instructions", toolName = "skill"),
            )),
        )
        compose.setContent { ActivityRow(groups, isLive = true) }
        compose.onNodeWithText("Read image · qa-shots/t10.png").assertDoesNotExist()
        compose.onNodeWithText("Could not load skill").assertDoesNotExist()

        val summary = processGroupSummary(groups.flatMap { it.operations }, closed = true).title()
        compose.onNodeWithText(summary).performClick()
        compose.onNodeWithText("Read image · qa-shots/t10.png").assertIsDisplayed()
        compose.onNodeWithText("Load skill · review").assertIsDisplayed()
        compose.onNodeWithText("Could not load skill").assertIsDisplayed()
        compose.onNodeWithText("Private instructions", substring = true).assertDoesNotExist()
    }


    @Test fun finishedWorkerHasOneOpenableRowWithAnUnclippedModelLine() {
        val model = "Example model with a long name · 16k · unknown price"
        var opened: String? = null
        val spawn = AgentActivityOperation("spawn", "Start worker", "Inspect docs\n$model",
            ActivityOutcome.Completed, toolName = "spawn_subagent", workerId = "worker")
        val await = spawn.copy(id = "await", title = "Await worker", toolName = "await_subagents")

        compose.setContent {
            Box(Modifier.width(160.dp)) {
                CompositionLocalProvider(LocalOpenTranscriptWorker provides { opened = it }) {
                    TranscriptWorkerRows(listOf(spawn, await))
                }
            }
        }

        compose.onNodeWithText("Inspect docs").performClick()
        assertEquals("worker", opened)
        val layout = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(model, useUnmergedTree = true).performSemanticsAction(
            SemanticsActions.GetTextLayoutResult,
        ) { action -> action(layout) }
        val input = layout.single().layoutInput
        assertTrue(input.softWrap)
        assertEquals(Int.MAX_VALUE, input.maxLines)
        assertTrue(input.constraints.maxWidth > 100)
    }

    @Test fun workerModelMetadataWrapsAtPhoneWidths() {
        val model = "Example model with more fields than this row can fit · 16k · unknown price"
        compose.setContent {
            Box(Modifier.width(160.dp)) {
                ActivityOperationRow(AgentActivityOperation("spawn", "Start worker", "Inspect docs\n$model",
                    ActivityOutcome.Running, toolName = "spawn_subagent", workerId = "worker"))
            }
        }
        val layout = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(model, useUnmergedTree = true).performSemanticsAction(
            SemanticsActions.GetTextLayoutResult,
        ) { action -> action(layout) }
        val input = layout.single().layoutInput
        assertTrue(input.softWrap)
        assertEquals(Int.MAX_VALUE, input.maxLines)
        assertTrue(input.constraints.maxWidth > 100)
    }

}
