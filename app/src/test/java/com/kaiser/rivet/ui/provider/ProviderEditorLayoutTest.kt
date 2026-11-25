package com.kaiser.rivet.ui.provider

import android.app.Application
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.kaiser.rivet.provider.ProviderConfig
import com.kaiser.rivet.provider.ProviderType
import com.kaiser.rivet.storage.ProviderStore
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ProviderEditorLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    @Config(qualifiers = "w360dp-h800dp-port")
    fun phonePortrait() = verifyBothEditors()

    @Test
    @Config(qualifiers = "w800dp-h360dp-land")
    fun phoneLandscape() = verifyBothEditors()

    @Test
    @Config(qualifiers = "sw600dp-w800dp-h1200dp-port")
    fun tabletPortrait() = verifyBothEditors()

    @Test
    @Config(qualifiers = "sw600dp-w1200dp-h800dp-land")
    fun tabletLandscape() = verifyBothEditors()

    private fun verifyBothEditors() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val type = ProviderType.OpenAiCompatible
        val config = ProviderConfig("test-layout", type, "Test provider", "https://example.test", "model")
        runBlocking { ProviderStore(app).save(config) }
        val viewModel = ProvidersViewModel(app, SecretKeySpec(ByteArray(16), "AES"))
        viewModel.startNewProvider(type)
        compose.setContent { ProviderEditor(viewModel, onDone = {}) }
        assertAligned("Add provider")

        compose.waitUntil(5_000) { viewModel.listState.value.configs.any { it.id == config.id } }
        compose.runOnIdle { viewModel.startEdit(config.id) }
        assertAligned("Change model")
    }

    private fun assertAligned(title: String) {
        compose.onNodeWithText(title).assertExists()
        val back = compose.onNodeWithContentDescription("Close").fetchSemanticsNode().boundsInRoot
        val form = compose.onAllNodes(hasSetTextAction())[0].fetchSemanticsNode().boundsInRoot
        val save = compose.onNodeWithText("Save").fetchSemanticsNode().boundsInRoot
        val cancel = compose.onNodeWithText("Cancel").fetchSemanticsNode().boundsInRoot
        assertEquals(form.left, back.left, 1f)
        assertEquals(form.left, save.left, 1f)
        assertEquals(form.right, cancel.right, 1f)
    }
}
