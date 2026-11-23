package com.kaiser.rivet.ui.chat

import android.app.Application
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.kaiser.rivet.agent.AgentMessage
import com.kaiser.rivet.browser.BrowserSession
import com.kaiser.rivet.chat.ChatUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
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
        lateinit var scope: CoroutineScope
        var density = 1f
        compose.setContent {
            scope = rememberCoroutineScope()
            density = LocalDensity.current.density
            MessageList(state.value, onDismissError = {}, onOpenSettings = {},
                onRetryProjectChanges = {}, onDiscardProjectChanges = {}, onRetryInterruptedTurn = {},
                onDismissInterruptedTurn = {}, onAttachGoal = {}, onRaiseBudget = {}, onRetryTurn = {},
                onChooseReasoning = {}, onOpenPreview = {}, missingFiles = emptySet(), browser = browser,
                modifier = Modifier.size(340.dp, 360.dp), listState = list)
        }

        fun scrollBy(dp: Float) {
            compose.runOnIdle {
                scope.launch {
                    list.scroll(MutatePriority.UserInput) {
                        scrollBy(dp * density)
                        repeat(2) { withFrameNanos {} }
                    }
                }
            }
            compose.runOnIdle { assertFalse(list.isScrollInProgress) }
        }

        compose.runOnIdle { assertFalse(list.canScrollForward) }
        scrollBy(-140f)
        scrollBy(100f)
        compose.runOnIdle {
            assertTrue(list.canScrollForward)
            state.value = state.value.copy(streaming = true, turnStartedAt = 1L, streamingText = "Writing")
        }
        compose.runOnIdle { assertTrue(list.canScrollForward) }

        compose.onNodeWithText("Jump to latest").performClick()
        compose.runOnIdle {
            assertFalse(list.canScrollForward)
            state.value = state.value.copy(streamingText = "A longer answer wraps across the viewport. ".repeat(12))
        }
        compose.runOnIdle { assertFalse(list.canScrollForward) }
    }
}
