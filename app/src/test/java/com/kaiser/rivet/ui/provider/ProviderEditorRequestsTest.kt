package com.kaiser.rivet.ui.provider

import com.kaiser.rivet.provider.ModelInfo
import com.kaiser.rivet.provider.ProviderConfig
import com.kaiser.rivet.provider.ProviderType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderEditorRequestsTest {

    @Test
    fun fetchedModelListIsBoundedAndSearchMatchesIdsAndLabelsCaseInsensitively() {
        val models = (1..460).map { ModelInfo("model-$it", "Provider choice $it") } +
            ModelInfo("special-id", "Nova Reasoner")

        val initial = filterModels(models, selectedModel = "special-id", query = "")
        val byId = filterModels(models, selectedModel = "special-id", query = "  SPECIAL-ID  ")
        val byLabel = filterModels(models, selectedModel = "model-1", query = "nova REASONER")

        assertTrue(initial.models.size <= MODEL_RESULTS_LIMIT)
        assertEquals(461, initial.totalCount)
        assertEquals(461, initial.matchCount)
        assertFalse(initial.selectedModelVisible)
        assertEquals(listOf("special-id"), byId.models.map { it.id })
        assertEquals(listOf("special-id"), byLabel.models.map { it.id })
        assertTrue(byLabel.selectedModelVisible.not())
        assertEquals(461, models.size)
        assertEquals("special-id", models.last().id)
    }

    @Test
    fun modelFilterReportsNoMatchesAndLeavesManualSelectionAuthoritative() {
        val models = listOf(ModelInfo("alpha", "Alpha"), ModelInfo("beta", "Beta"))
        val manualModel = "private/manual-model"

        val result = filterModels(models, selectedModel = manualModel, query = "missing")

        assertTrue(result.models.isEmpty())
        assertEquals(0, result.matchCount)
        assertFalse(result.selectedModelVisible)
        val state = ProviderEditorState(
            config = ProviderConfig(id = "provider", type = ProviderType.OpenAiCompatible,
                name = "Provider", baseUrl = "https://example.test/v1", model = manualModel),
            models = models,
        )
        assertEquals(manualModel, state.config.model)
        assertEquals(listOf("alpha", "beta"), models.map { it.id })
    }

    @Test
    fun newProviderNamesAreHumanReadableInsteadOfEnumNames() {
        ProviderType.values().forEach { type ->
            assertFalse(providerDefaultName(type).contains("OpenAiCompatible"))
            assertFalse(providerDefaultName(type).contains("OpenAi"))
        }
        assertTrue(providerDefaultName(ProviderType.OpenAiCompatible) == "OpenAI-compatible")
        assertTrue(providerDefaultName(ProviderType.OpenAi) == "OpenAI")
        assertTrue(providerDefaultName(ProviderType.Anthropic) == "Anthropic")
        assertTrue(providerDefaultName(ProviderType.Gemini) == "Gemini")
        assertTrue(providerDefaultName(ProviderType.OpenRouter) == "OpenRouter")
    }

    @Test
    fun onlyOneEditorRequestRunsAndCancellationClearsBusyState() = runTest {
        val owner = ProviderEditorRequests(this)
        val started = CompletableDeferred<ProviderEditorRequests.Ticket>()
        var busy = true
        var fetching = true

        assertTrue(owner.tryLaunch("provider-a") { ticket ->
            started.complete(ticket)
            awaitCancellation()
        })
        val ticket = started.await()
        assertFalse(owner.tryLaunch("provider-a") { error("second request ran") })

        owner.cancel {
            busy = false
            fetching = false
        }
        advanceUntilIdle()

        assertFalse(busy)
        assertFalse(fetching)
        assertFalse(owner.running)
        assertFalse(owner.isCurrent(ticket, "provider-a"))
    }

    @Test
    fun responseFromReplacedEditorIsStale() = runTest {
        val owner = ProviderEditorRequests(this)
        val started = CompletableDeferred<ProviderEditorRequests.Ticket>()
        val replacement = CompletableDeferred<ProviderEditorRequests.Ticket>()

        assertTrue(owner.tryLaunch("provider-a") { ticket ->
            started.complete(ticket)
            awaitCancellation()
        })
        val oldTicket = started.await()
        owner.cancel {}
        assertTrue(owner.tryLaunch("provider-b") { ticket ->
            replacement.complete(ticket)
            awaitCancellation()
        })
        val newTicket = replacement.await()
        assertFalse(owner.isCurrent(oldTicket, "provider-a"))
        assertTrue(owner.isCurrent(newTicket, "provider-b"))
        owner.cancel {}
    }

    @Test
    fun changingEndpointClearsResultsFromOldConfiguration() {
        val state = ProviderEditorState(
            config = ProviderConfig(
                id = "provider-a",
                type = ProviderType.OpenAiCompatible,
                name = "A",
                baseUrl = "https://old.example/v1",
                model = "old-model",
            ),
            test = TestUi(true, "old result"),
            models = listOf(ModelInfo("old-model", "Old")),
            fetchError = "old error",
        )

        val changed = state.withConfigUpdate {
            it.copy(baseUrl = "https://new.example/v1")
        }

        assertTrue(changed.models.isEmpty())
        assertTrue(changed.test == null)
        assertTrue(changed.fetchError == null)
    }

    @Test
    fun changingOnlyModelKeepsFetchedChoices() {
        val models = listOf(ModelInfo("model-a", "A"), ModelInfo("model-b", "B"))
        val state = ProviderEditorState(
            config = ProviderConfig(
                id = "provider-a",
                type = ProviderType.OpenAiCompatible,
                name = "A",
                baseUrl = "https://example.test/v1",
                model = "model-a",
            ),
            models = models,
        )

        val changed = state.withConfigUpdate { it.copy(model = "model-b") }

        assertTrue(changed.models === models)
    }
}
