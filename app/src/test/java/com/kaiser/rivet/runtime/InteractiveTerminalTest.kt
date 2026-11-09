package com.kaiser.rivet.runtime

import com.termux.terminal.TerminalEmulator
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InteractiveTerminalTest {
    private class FakeEngine(
        private val observer: TerminalObserver,
        val launch: TerminalLaunch,
    ) : TerminalEngine {
        override val pid: Int = 4242
        override val emulator: TerminalEmulator? = null
        var closed = false
            private set
        var resizes = 0
            private set

        override fun write(text: String) = Unit
        override fun resize(columns: Int, rows: Int, cellWidthPx: Int, cellHeightPx: Int) {
            resizes++
        }
        override fun close() { closed = true }
        fun exit(code: Int) = observer.exited(code)
    }

    private class Harness(
        val holder: InteractiveTerminalSession,
        val engines: MutableList<FakeEngine>,
        val launches: () -> Int,
        val closed: MutableList<String>,
        val exits: MutableList<Pair<String, Int>>,
        val setIdentity: (String) -> Unit,
    )

    private fun harness(scope: kotlinx.coroutines.CoroutineScope): Harness {
        val engines = mutableListOf<FakeEngine>()
        val closed = mutableListOf<String>()
        val exits = mutableListOf<Pair<String, Int>>()
        var launches = 0
        var identity = "A"
        val holder = InteractiveTerminalSession(
            scope = scope,
            launchFactory = {
                launches++
                TerminalLaunch(managedId = "m$launches", workspaceIdentity = identity)
            },
            engineFactory = TerminalEngineFactory { launch, observer ->
                FakeEngine(observer, launch).also { engines += it }
            },
            onNaturalExit = { id, code -> exits += id to code },
            onClosed = { id -> closed += id },
            treeWatcher = object : TerminalTreeWatcher {
                override suspend fun watch(pid: Int, memoryCapBytes: Long?,
                                           terminate: suspend () -> Unit): String? = null
            },
            killGroup = { _ -> },
        )
        return Harness(holder, engines, { launches }, closed, exits,
            { value -> identity = value })
    }

    @Test fun startsOnceAndReusesTheSessionAcrossReopen() = runTest {
        val harness = harness(this)
        harness.holder.open("A")
        harness.holder.ensureStarted(80, 24, 8, 16)
        advanceUntilIdle()
        val first = harness.engines.single()

        harness.holder.open("A")
        harness.holder.ensureStarted(100, 30, 8, 16)
        advanceUntilIdle()

        assertEquals(1, harness.launches())
        assertEquals(1, harness.engines.size)
        assertTrue(harness.holder.state.value.running)
        assertTrue(first.resizes >= 2)
        assertTrue(harness.closed.isEmpty())
    }

    @Test fun projectSwitchClosesTheOldGroupAndStartsANewSession() = runTest {
        val harness = harness(this)
        harness.holder.open("A")
        harness.holder.ensureStarted(80, 24, 8, 16)
        advanceUntilIdle()
        val old = harness.engines.single()

        harness.setIdentity("B")
        harness.holder.open("B")
        harness.holder.ensureStarted(80, 24, 8, 16)
        advanceUntilIdle()

        assertEquals(2, harness.launches())
        assertTrue(old.closed)
        assertEquals(listOf("m1"), harness.closed)
        assertEquals("A", old.launch.workspaceIdentity)
        assertEquals("B", harness.engines.last().launch.workspaceIdentity)
    }

    @Test fun aShellExitBecomesTheEndedStateAndFinishesTheManagedEntry() = runTest {
        val harness = harness(this)
        harness.holder.open("A")
        harness.holder.ensureStarted(80, 24, 8, 16)
        advanceUntilIdle()
        val engine = harness.engines.single()

        engine.exit(7)
        advanceUntilIdle()

        assertTrue(harness.holder.state.value.ended)
        assertEquals(7, harness.holder.state.value.exitCode)
        assertEquals(listOf("m1" to 7), harness.exits)
        assertTrue(engine.closed)
        assertFalse(harness.holder.state.value.running)
    }

    @Test fun explicitCloseEndsTheSessionAndIsNotANaturalExit() = runTest {
        val harness = harness(this)
        harness.holder.open("A")
        harness.holder.ensureStarted(80, 24, 8, 16)
        advanceUntilIdle()
        val engine = harness.engines.single()

        harness.holder.close()
        advanceUntilIdle()

        assertTrue(harness.holder.state.value.ended)
        assertTrue(engine.closed)
        assertEquals(listOf("m1"), harness.closed)
        assertTrue(harness.exits.isEmpty())
    }

    @Test fun restartAfterExitStartsExactlyOneNewSession() = runTest {
        val harness = harness(this)
        harness.holder.open("A")
        harness.holder.ensureStarted(80, 24, 8, 16)
        advanceUntilIdle()
        harness.engines.single().exit(0)
        advanceUntilIdle()

        harness.holder.restart()
        advanceUntilIdle()

        assertEquals(2, harness.launches())
        assertEquals(2, harness.engines.size)
        assertTrue(harness.holder.state.value.running)
        assertFalse(harness.holder.state.value.ended)
    }
}
