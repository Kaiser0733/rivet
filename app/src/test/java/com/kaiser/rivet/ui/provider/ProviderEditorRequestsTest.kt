package com.kaiser.rivet.ui.provider

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

        assertTrue(owner.tryLaunch("provider-a") { ticket ->
            started.complete(ticket)
            awaitCancellation()
        })
        val oldTicket = started.await()
        owner.cancel {}

        assertFalse(owner.isCurrent(oldTicket, "provider-b"))
    }
}
