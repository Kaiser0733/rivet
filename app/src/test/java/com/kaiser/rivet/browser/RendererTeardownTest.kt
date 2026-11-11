package com.kaiser.rivet.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RendererTeardownTest {
    @Test fun destroyIsRefusedUntilTheHostHasReleasedTheView() {
        val teardown = RendererTeardown()
        assertEquals(RendererTeardown.State.Live, teardown.state)
        assertFalse("destroy must not run while the view is still attached", teardown.destroy())
        assertEquals(RendererTeardown.State.Live, teardown.state)
    }

    @Test fun theOnlyLegalSequenceIsDetachThenReleaseThenDestroy() {
        val teardown = RendererTeardown()
        assertTrue(teardown.requestDetach())
        assertEquals(RendererTeardown.State.DetachRequested, teardown.state)
        assertTrue(teardown.release())
        assertEquals(RendererTeardown.State.Released, teardown.state)
        assertTrue(teardown.destroy())
        assertEquals(RendererTeardown.State.Destroyed, teardown.state)
    }

    @Test fun detachAndReleaseAreIdempotentAndOutOfOrderCallsAreRefused() {
        val teardown = RendererTeardown()
        assertFalse(teardown.release())
        assertTrue(teardown.requestDetach())
        assertFalse(teardown.requestDetach())
        assertTrue(teardown.release())
        assertFalse(teardown.release())
        assertTrue(teardown.destroy())
        assertFalse(teardown.destroy())
    }
}
