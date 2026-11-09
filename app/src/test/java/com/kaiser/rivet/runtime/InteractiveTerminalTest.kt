package com.kaiser.rivet.runtime

import com.termux.terminal.TerminalEmulator
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
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
        var lastSize: Triple<Int, Int, Int>? = null
            private set

        override fun write(text: String) = Unit
        override fun resize(columns: Int, rows: Int, cellWidthPx: Int, cellHeightPx: Int) {
            resizes++
            lastSize = Triple(columns, rows, cellWidthPx)
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
        val stops: MutableList<String>,
        val setIdentity: (String) -> Unit,
    )

    private fun harness(
        scope: kotlinx.coroutines.CoroutineScope,
        treeWatcher: TerminalTreeWatcher = object : TerminalTreeWatcher {
            override suspend fun watch(pid: Int, memoryCapBytes: Long?,
                                       terminate: suspend () -> Unit): String? = null
        },
        killDescendants: (Int) -> Unit = {},
    ): Harness {
        val engines = mutableListOf<FakeEngine>()
        val closed = mutableListOf<String>()
        val exits = mutableListOf<Pair<String, Int>>()
        val stops = mutableListOf<String>()
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
            onStopRecorded = { reason -> stops += reason },
            treeWatcher = treeWatcher,
            killGroup = { _ -> },
            killDescendants = killDescendants,
        )
        return Harness(holder, engines, { launches }, closed, exits, stops,
            { value -> identity = value })
    }

    private suspend fun settle() {
        // Let the holder's launch, start and watcher coroutines take their turns on the
        // test dispatcher without depending on an experimental scheduler API.
        repeat(4) { yield() }
    }

    @Test fun startsOnceAndReusesTheSessionAcrossReopen() = runTest {
        val harness = harness(this)
        harness.holder.open("A")
        harness.holder.ensureStarted(80, 24, 8, 16)
        settle()
        val first = harness.engines.single()

        harness.holder.open("A")
        harness.holder.ensureStarted(100, 30, 8, 16)
        delay(TERMINAL_RESIZE_DEBOUNCE_MS + 1)

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
        settle()
        val old = harness.engines.single()

        harness.setIdentity("B")
        harness.holder.open("B")
        harness.holder.ensureStarted(80, 24, 8, 16)
        settle()

        assertEquals(2, harness.launches())
        assertTrue(old.closed)
        assertEquals(listOf("m1"), harness.closed)
        assertEquals("A", old.launch.workspaceIdentity)
        assertEquals("B", harness.engines.last().launch.workspaceIdentity)
    }

    @Test fun rapidSizeChangesReachThePtyOnceAtTheFinalSize() = runTest {
        val harness = harness(this)
        harness.holder.open("A")
        harness.holder.ensureStarted(80, 24, 8, 16)
        settle()
        val engine = harness.engines.single()
        assertEquals(1, engine.resizes)

        harness.holder.ensureStarted(81, 24, 8, 16)
        harness.holder.ensureStarted(95, 27, 8, 16)
        harness.holder.ensureStarted(100, 30, 8, 16)
        delay(149)
        assertEquals(1, engine.resizes)

        delay(2)
        assertEquals(2, engine.resizes)
        assertEquals(Triple(100, 30, 8), engine.lastSize)

        harness.holder.ensureStarted(100, 30, 8, 16)
        delay(200)
        assertEquals(2, engine.resizes)
    }

    @Test fun watchdogStopsDescendantsButLeavesTheShellPromptAlive() = runTest {
        val stops = mutableListOf<String>()
        val killed = mutableListOf<Int>()
        var calls = 0
        val watcher = object : TerminalTreeWatcher {
            override suspend fun watch(pid: Int, memoryCapBytes: Long?,
                                       terminate: suspend () -> Unit): String? {
                calls++
                if (calls == 1) terminate()
                return if (calls == 1) "memory limit" else null
            }
        }
        val harness = harness(this, watcher) { root -> killed += root }
        harness.holder.open("A")
        harness.holder.ensureStarted(80, 24, 8, 16)
        settle()
        val engine = harness.engines.single()

        assertTrue(harness.holder.state.value.running)
        assertFalse(engine.closed)
        assertEquals(listOf(4242), killed)
        assertEquals(listOf("memory limit"), harness.stops)
    }

    @Test fun aShellExitBecomesTheEndedStateAndFinishesTheManagedEntry() = runTest {
        val harness = harness(this)
        harness.holder.open("A")
        harness.holder.ensureStarted(80, 24, 8, 16)
        settle()
        val engine = harness.engines.single()

        engine.exit(7)
        settle()

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
        settle()
        val engine = harness.engines.single()

        harness.holder.close()
        settle()

        assertTrue(harness.holder.state.value.ended)
        assertTrue(engine.closed)
        assertEquals(listOf("m1"), harness.closed)
        assertTrue(harness.exits.isEmpty())
    }

    @Test fun restartAfterExitStartsExactlyOneNewSession() = runTest {
        val harness = harness(this)
        harness.holder.open("A")
        harness.holder.ensureStarted(80, 24, 8, 16)
        settle()
        harness.engines.single().exit(0)
        settle()

        harness.holder.restart()
        settle()

        assertEquals(2, harness.launches())
        assertEquals(2, harness.engines.size)
        assertTrue(harness.holder.state.value.running)
        assertFalse(harness.holder.state.value.ended)
    }
}
