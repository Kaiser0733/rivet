package com.kaiser.rivet.ui.chat

import android.app.Application
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.kaiser.rivet.agent.AgentMessage
import com.kaiser.rivet.browser.BrowserSession
import com.kaiser.rivet.chat.ChatUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TranscriptScrollTest {
    @get:Rule val compose = createComposeRule()

    @Test fun scrollingTowardTheEndDoesNotReattachUntilTheReaderJumpsToLatest() {
        val app: Application = RuntimeEnvironment.getApplication()
        val browser = BrowserSession(app, models = null)
        val state = mutableStateOf(ChatUiState(messages = (1..20).flatMap { turn ->
            listOf(AgentMessage.user("Question $turn"), AgentMessage.assistant("Answer $turn"))
        }))
        val list = LazyListState()
        compose.setContent {
            MessageList(state.value, onDismissError = {}, onOpenSettings = {},
                onRetryProjectChanges = {}, onDiscardProjectChanges = {}, onRetryInterruptedTurn = {},
                onDismissInterruptedTurn = {}, onAttachGoal = {}, onRaiseBudget = {}, onRetryTurn = {},
                onChooseReasoning = {}, onOpenPreview = {}, missingFiles = emptySet(), browser = browser,
                modifier = Modifier.size(340.dp, 360.dp), listState = list)
        }

        // A slow finger drag, so no fling: a positive direction moves the finger down and
        // walks the transcript toward older turns, a negative one back toward the end.
        fun drag(direction: Float, steps: Int) {
            compose.onNode(hasScrollToIndexAction()).performTouchInput {
                down(center)
                repeat(steps) {
                    advanceEventTime(250)
                    moveBy(Offset(0f, direction * visibleSize.height / 6f))
                }
                advanceEventTime(600)
                up()
            }
            compose.waitForIdle()
        }

        compose.runOnIdle { assertFalse(list.canScrollForward) }
        drag(direction = 1f, steps = 4)
        compose.runOnIdle { assertTrue(list.canScrollForward) }
        drag(direction = -1f, steps = 2)
        val stoppedShort = compose.runOnIdle {
            assertTrue(list.canScrollForward)
            assertFalse(list.isScrollInProgress)
            list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset
        }

        // The reply keeps growing while the reader is stopped short of the end: the list
        // must not move a single row, and follow must stay suspended.
        compose.runOnIdle {
            state.value = state.value.copy(streaming = true, turnStartedAt = 1L, streamingText = "Writing")
        }
        compose.runOnIdle {
            assertTrue(list.canScrollForward)
            assertEquals(stoppedShort, list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset)
        }

        compose.onNodeWithText("Jump to latest").performClick()
        compose.runOnIdle { assertFalse(list.canScrollForward) }
        compose.runOnIdle {
            state.value = state.value.copy(streamingText = "A longer answer wraps across the viewport. ".repeat(12))
        }
        compose.runOnIdle { assertFalse(list.canScrollForward) }

        // A gesture that walks all the way back to the end re-attaches follow by itself.
        drag(direction = 1f, steps = 3)
        compose.runOnIdle { assertTrue(list.canScrollForward) }
        drag(direction = -1f, steps = 6)
        compose.runOnIdle { assertFalse(list.canScrollForward) }
        compose.runOnIdle {
            state.value = state.value.copy(streamingText = "Followed again. ".repeat(20))
        }
        compose.runOnIdle { assertFalse(list.canScrollForward) }
    }
}
