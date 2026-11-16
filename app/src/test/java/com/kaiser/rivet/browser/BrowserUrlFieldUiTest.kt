package com.kaiser.rivet.browser

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextRange
import com.kaiser.rivet.ui.browser.BrowserScreen
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BrowserUrlFieldUiTest {
    @get:Rule val compose = createComposeRule()

    private fun sessionAt(url: String): BrowserSession {
        val app: Application = RuntimeEnvironment.getApplication()
        val session = BrowserSession(app, models = null)
        val field = BrowserSession::class.java.getDeclaredField("_state")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val state = field.get(session) as MutableStateFlow<BrowserUiState>
        state.value = state.value.copy(
            visible = true,
            url = url,
            pageReady = true,
            activeWindowId = "test-window",
        )
        return session
    }

    private fun show(session: BrowserSession) {
        compose.setContent {
            BrowserScreen(session = session, onClose = {})
        }
        compose.waitForIdle()
    }

    private fun showWithInteraction(
        session: BrowserSession,
        onPageInteraction: AtomicReference<(() -> Unit)?>,
    ) {
        compose.setContent {
            BrowserScreen(
                session = session,
                onClose = {},
                hostContent = { modifier, interaction ->
                    onPageInteraction.set(interaction)
                    Box(modifier)
                },
            )
        }
        compose.waitForIdle()
    }

    private fun selection(): TextRange =
        compose.onNode(hasSetTextAction()).fetchSemanticsNode()
            .config[SemanticsProperties.TextSelectionRange]

    private fun tapUrl() {
        compose.onNode(hasSetTextAction()).performTouchInput { click() }
        compose.waitForIdle()
    }

    @Test fun unfocusedMidStringTapSelectsTheWholeUrlAndTypingReplacesIt() {
        val url = "https://example.org/"
        val session = sessionAt(url)
        show(session)

        tapUrl()
        assertEquals(TextRange(0, url.length), selection())

        compose.onNode(hasSetTextAction()).performTextInput("abc")
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).assertTextEquals("abc")
    }

    @Test fun pageInteractionClearsFocusSoTheNextTapSelectsAllAgain() {
        val url = "https://example.org/"
        val session = sessionAt(url)
        val interaction = AtomicReference<(() -> Unit)?>(null)
        showWithInteraction(session, interaction)

        tapUrl()
        assertEquals(TextRange(0, url.length), selection())

        compose.runOnIdle { interaction.get()!!.invoke() }
        compose.waitForIdle()
        tapUrl()
        assertEquals(TextRange(0, url.length), selection())
    }

    @Test fun secondTapWhileFocusedCollapsesToACaret() {
        val url = "https://example.org/"
        val session = sessionAt(url)
        show(session)

        tapUrl()
        assertEquals(TextRange(0, url.length), selection())

        tapUrl()
        assertTrue(selection().collapsed)
    }
}
