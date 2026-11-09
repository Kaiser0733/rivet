package com.kaiser.rivet.agent

import com.kaiser.rivet.runtime.ToolchainAvailability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolchainContextTest {
    private val all = ToolchainAvailability(busybox = true, python = true, git = true)

    @Test fun theLineReflectsTheAvailabilityItIsGiven() {
        val present = ToolchainContext.line(all)
        assertTrue(present.contains("BusyBox available"))
        assertTrue(present.contains("Python 3.12.3 available"))
        assertTrue(present.contains("Git 2.56 available"))
        assertFalse(present.contains("unavailable"))

        val absent = ToolchainContext.line(ToolchainAvailability())
        assertTrue(absent.contains("BusyBox unavailable"))
        assertTrue(absent.contains("Python 3.12.3 unavailable"))
        assertTrue(absent.contains("Git 2.56 unavailable"))
    }

    @Test fun updatesOnlyWhenTheObservedDigestChanges() {
        val messages = mutableListOf<AgentMessage>()
        val first = ToolchainContext.updates(messages, "ws://tree", all)
        assertEquals(1, first.size)
        messages += first

        assertTrue(ToolchainContext.updates(messages, "ws://tree", all).isEmpty())

        val changed = ToolchainContext.updates(messages, "ws://tree", all.copy(git = false))
        val context = changed.single().internalContext
        assertTrue(context != null)
        assertEquals(ToolchainContext.KIND, context!!.kind)
        assertTrue(context.content.contains("Git 2.56 unavailable"))
    }
}
