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

    @Test fun aSecondTeardownRequestStillForcesAPendingDetach() {
        val requested = RendererTeardown()
        requested.requestDetach()
        assertEquals(RendererTeardown.State.DetachRequested, requested.state)
        // The session must publish teardownRequested even when requestDetach is false.
        assertTrue(requested.forceRequestDetach())
        assertEquals(RendererTeardown.State.DetachRequested, requested.state)

        val released = RendererTeardown()
        released.requestDetach()
        released.release()
        assertEquals(RendererTeardown.State.Released, released.state)
        assertTrue(released.forceRequestDetach())
        assertEquals(RendererTeardown.State.DetachRequested, released.state)
    }

    @Test fun attachingANewViewExplicitlySupersedesAPendingDetach() {
        val pending = RendererTeardown()
        pending.requestDetach()
        assertEquals(RendererTeardown.State.DetachRequested, pending.prepareAttach())
        assertEquals(RendererTeardown.State.Live, pending.state)
        assertTrue("a superseded detach must not look pending", pending.requestDetach())
        assertEquals(RendererTeardown.State.DetachRequested, pending.prepareAttach())
        assertEquals(RendererTeardown.State.Live, pending.state)
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
