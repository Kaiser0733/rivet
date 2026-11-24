package com.kaiser.rivet.ui.provider

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ProviderEditorFieldTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theSettingsLabelStaysAboveTheEditorWhenEmptyAndWhenTypedInto() {
        compose.setContent {
            var value by remember { mutableStateOf("") }
            ProviderEditorField(value, { value = it }, label = "Model", singleLine = true)
        }
        val heading = compose.onNodeWithText("Model", useUnmergedTree = true)
        val input = compose.onNode(hasSetTextAction())
        val emptyHeading = heading.fetchSemanticsNode().boundsInRoot
        assertTrue(emptyHeading.bottom <= input.fetchSemanticsNode().boundsInRoot.top)
        input.performTextInput("example-model")
        val filledHeading = heading.fetchSemanticsNode().boundsInRoot
        assertTrue(filledHeading == emptyHeading)
    }
}
