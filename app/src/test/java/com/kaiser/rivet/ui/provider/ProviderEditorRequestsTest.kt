package com.kaiser.rivet.ui.provider

import com.kaiser.rivet.provider.ModelInfo
import com.kaiser.rivet.provider.ProviderConfig
import com.kaiser.rivet.provider.ProviderType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderEditorRequestsTest {

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
