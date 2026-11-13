package com.kaiser.rivet.runtime

import android.util.Log
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import java.io.File
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What RuntimeController hands to the PTY owner after admission and environment setup. */
internal data class TerminalLaunch(
    val shellPath: String = SYSTEM_SHELL,
    val cwd: String = "",
    val environment: Array<String> = emptyArray(),
    val managedId: String? = null,
    val workspaceIdentity: String = "",
    val memoryCapBytes: Long? = null,
    val error: String? = null,
)

internal const val TERMINAL_RESIZE_DEBOUNCE_MS = 150L

internal data class InteractiveTerminalState(
    val running: Boolean = false,
    val starting: Boolean = false,
    val ended: Boolean = false,
    val error: String? = null,
    val exitCode: Int? = null,
    val pid: Int = 0,
)

/** The small surface the lifecycle owner needs from a PTY, fakeable in JVM tests. */
internal interface TerminalEngine {
    val pid: Int
    val emulator: TerminalEmulator?
    fun write(text: String)
    fun resize(columns: Int, rows: Int, cellWidthPx: Int, cellHeightPx: Int)
    fun close()
}

internal interface TerminalObserver {
    fun changed()
    fun exited(exitCode: Int)
}

internal fun interface TerminalEngineFactory {
    fun create(launch: TerminalLaunch, observer: TerminalObserver): TerminalEngine
}

/** The existing descendant/PSS watchdog, injectable so lifecycle tests need no /proc. */
internal interface TerminalTreeWatcher {
    suspend fun watch(pid: Int, memoryCapBytes: Long?,
                      terminate: suspend () -> Unit): String?
}

/**
 * Owns the one interactive shell. It starts only when the pane has a real grid size,
 * keeps the PTY across slide close and rotation, and closes the process group on an
 * explicit close or a project switch.
 */
internal class InteractiveTerminalSession(
    private val scope: CoroutineScope,
    private val launchFactory: suspend (Job) -> TerminalLaunch,
    private val onNaturalExit: (String, Int) -> Unit = { _, _ -> },
    private val onClosed: (String) -> Unit = {},
    private val onStopRecorded: (String) -> Unit = {},
    private val onSync: suspend (String) -> Unit = {},
    private val engineFactory: TerminalEngineFactory = PtyTerminalEngineFactory,
    private val treeWatcher: TerminalTreeWatcher = DefaultTerminalTreeWatcher,
    private val killGroup: (Int) -> Unit = { pid ->
        if (pid > 0) ProcTree.sessionMembers(pid).forEach(ProcTree::kill)
    },
    // The interactive shell survives a watchdog stop: kill its work, not the prompt.
    private val killDescendants: (Int) -> Unit = { root ->
        if (root > 0) {
            ProcTree.sessionMembers(root).filter { it != root }.forEach(ProcTree::kill)
        }
    },
) {
    private val mutableState = MutableStateFlow(InteractiveTerminalState())
    val state: StateFlow<InteractiveTerminalState> = mutableState.asStateFlow()

    private val mutableRevision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = mutableRevision.asStateFlow()

    private var engine: TerminalEngine? = null
    private var launch: TerminalLaunch? = null
    private var sessionJob: Job? = null
    private var watcherJob: Job? = null
    private var requested = false
    private var starting = false
    private var finished = false
    private var generation = 0L
    private var columns = 80
    private var rows = 24
    private var cellWidthPx = 1
    private var cellHeightPx = 1

    // The PTY sees only a stable grid. IME and layout animations emit several
    // intermediate sizes; sending each one makes ash reprint its prompt.
    private var appliedColumns = 0
    private var appliedRows = 0
    private var appliedCellWidthPx = 0
    private var appliedCellHeightPx = 0
    private var resizeJob: Job? = null

    val emulator: TerminalEmulator? get() = engine?.emulator

    /** Called by the New terminal card; an ended session is cleared for a fresh start. */
    fun open(projectIdentity: String? = null) {
        // A mirror can move/recreate its worktree while this object (or its saved
        // panel) outlives the directory the PTY was started in. Re-resolve on restore:
        // a dead cwd is unusable, so close that shell and let ensureStarted launch a
        // fresh one in the current worktree.
        val liveCwd = launch?.cwd
        val existing = launch?.workspaceIdentity
        when (TerminalStartGate.decideOpenReset(
            hasLiveEngine = engine != null,
            isStarting = starting,
            liveCwd = liveCwd,
            cwdExists = liveCwd.isNullOrEmpty() || File(liveCwd).isDirectory,
            projectIdentity = projectIdentity,
            existingIdentity = existing,
            jobCancelled = sessionJob?.isCancelled == true,
        )) {
            TerminalOpenReset.CLOSE_SILENT -> closeInternal(markEnded = false)
            TerminalOpenReset.CLOSE_ENDED -> close()
            TerminalOpenReset.NONE -> Unit
        }
        if (finished) {
            finished = false
            mutableState.update { it.copy(ended = false, error = null, exitCode = null) }
        }
        requested = true
    }

    /** Starts with the pane's grid; later calls only resize the live PTY. */
    fun ensureStarted(columns: Int, rows: Int, cellWidthPx: Int, cellHeightPx: Int) {
        // TerminalEmulator.resize throws below 2x2; a transient zero-sized pane
        // during IME animation must not take the UI down.
        this.columns = columns.coerceAtLeast(2)
        this.rows = rows.coerceAtLeast(2)
        this.cellWidthPx = cellWidthPx.coerceAtLeast(1)
        this.cellHeightPx = cellHeightPx.coerceAtLeast(1)
        val live = engine
        if (live != null) {
            requestResize(live)
            return
        }
        if (!requested || starting || finished) return
        starting = true
        mutableState.update { it.copy(starting = true, running = false, ended = false, error = null) }
        val ticket = ++generation
        val job = Job()
        sessionJob = job
        job.invokeOnCompletion {
            // A stop from the Processes surface cancels this job from its caller's thread.
            // Hop cleanup onto scope so engine creation/close stay serialized on the session owner.
            scope.launch {
                if (ticket == generation && !finished) closeInternal(markEnded = true)
            }
        }
        scope.launch {
            val launch = try {
                launchFactory(job)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                TerminalLaunch(error = "runtime_unavailable")
            }
            if (!TerminalStartGate.canCompleteLaunch(
                    ticket = ticket,
                    currentGeneration = generation,
                    finished = finished,
                    requested = requested,
                    jobActive = job.isActive,
                )) {
                launch.managedId?.let(onClosed)
                if (ticket == generation && !finished && !job.isActive) {
                    closeInternal(markEnded = true)
                }
                return@launch
            }
            if (launch.error != null) {
                failStart(launch.error, launch.managedId)
                return@launch
            }
            this@InteractiveTerminalSession.launch = launch
            val created = try {
                engineFactory.create(launch, observer)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (!TerminalStartGate.canAcceptCreatedEngine(
                    ticket = ticket,
                    currentGeneration = generation,
                    finished = finished,
                    requested = requested,
                    jobActive = job.isActive,
                )) {
                if (created != null) {
                    created.close()
                    killSession(created.pid)
                }
                if (ticket == generation && !finished) {
                    closeInternal(markEnded = !job.isActive)
                }
                return@launch
            }
            if (created == null) {
                failStart("runtime_unavailable", launch.managedId)
                return@launch
            }
            engine = created
            starting = false
            try {
                created.resize(this@InteractiveTerminalSession.columns,
                    this@InteractiveTerminalSession.rows, cellWidthPx, cellHeightPx)
                appliedColumns = this@InteractiveTerminalSession.columns
                appliedRows = this@InteractiveTerminalSession.rows
                appliedCellWidthPx = this@InteractiveTerminalSession.cellWidthPx
                appliedCellHeightPx = this@InteractiveTerminalSession.cellHeightPx
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                closeInternal(markEnded = true)
                return@launch
            }
            // A failed exec calls onSessionFinished synchronously from updateSize.
            if (finished || mutableState.value.ended || created.pid <= 0) {
                if (!finished) closeInternal(markEnded = true)
                return@launch
            }
            mutableState.update { it.copy(running = true, starting = false, ended = false,
                error = null, pid = created.pid) }
            // The shell may already have exited between the state update and this line.
            if (!finished && created.pid > 0) {
                watcherJob = scope.launch { watchTree(ticket, created, launch) }
            }
        }
    }

    fun write(text: String) {
        if (text.isNotEmpty()) engine?.write(text)
    }

    fun resize(columns: Int, rows: Int, cellWidthPx: Int, cellHeightPx: Int) {
        this.columns = columns.coerceAtLeast(2)
        this.rows = rows.coerceAtLeast(2)
        this.cellWidthPx = cellWidthPx.coerceAtLeast(1)
        this.cellHeightPx = cellHeightPx.coerceAtLeast(1)
        engine?.let(::requestResize)
    }

    private fun requestResize(live: TerminalEngine) {
        if (finished) return
        if (columns == appliedColumns && rows == appliedRows &&
            cellWidthPx == appliedCellWidthPx && cellHeightPx == appliedCellHeightPx) {
            resizeJob?.cancel()
            resizeJob = null
            return
        }
        // IME show/hide only changes the number of rows. ash treats every SIGWINCH
        // as a reason to print another prompt; there is nothing useful to reflow at
        // the prompt, so accept the new row count without waking the PTY. A later
        // column change still applies the current rows in one resize.
        if (columns == appliedColumns && cellWidthPx == appliedCellWidthPx &&
            cellHeightPx == appliedCellHeightPx && rows != appliedRows) {
            appliedRows = rows
            resizeJob?.cancel()
            resizeJob = null
            return
        }
        resizeJob?.cancel()
        resizeJob = scope.launch {
            delay(TERMINAL_RESIZE_DEBOUNCE_MS)
            if (finished) return@launch
            val current = engine ?: return@launch
            if (columns == appliedColumns && rows == appliedRows &&
                cellWidthPx == appliedCellWidthPx && cellHeightPx == appliedCellHeightPx) {
                return@launch
            }
            try {
                current.resize(columns, rows, cellWidthPx, cellHeightPx)
                appliedColumns = columns
                appliedRows = rows
                appliedCellWidthPx = cellWidthPx
                appliedCellHeightPx = cellHeightPx
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                closeInternal(markEnded = true)
            }
        }
    }

    fun close() = closeInternal(markEnded = true)

    fun restart() {
        closeInternal(markEnded = false)
        finished = false
        requested = true
        ensureStarted(columns, rows, cellWidthPx, cellHeightPx)
    }

    private val observer = object : TerminalObserver {
        override fun changed() {
            mutableRevision.update { it + 1 }
        }

        override fun exited(exitCode: Int) {
            finishNatural(exitCode)
        }
    }

    private suspend fun watchTree(ticket: Long, engine: TerminalEngine,
                                  launch: TerminalLaunch) {
        // Keep watching after a stop: the shell is deliberately spared, and a later
        // command must be governed too. terminate() kills only descendants.
        while (!finished && ticket == generation && engine.pid > 0) {
            val reason = treeWatcher.watch(engine.pid, launch.memoryCapBytes) {
                killDescendants(engine.pid)
            } ?: return
            onStopRecorded(reason)
        }
    }

    private fun failStart(error: String, managedId: String?) {
        starting = false
        finished = true
        launch = null
        val job = sessionJob
        sessionJob = null
        job?.cancel()
        mutableState.update { it.copy(starting = false, running = false,
            ended = true, error = error) }
        managedId?.let(onClosed)
    }

    private fun finishNatural(exitCode: Int) {
        if (finished) return
        finished = true
        requested = false
        starting = false
        generation++
        watcherJob?.cancel()
        watcherJob = null
        resizeJob?.cancel()
        resizeJob = null
        val ended = engine
        engine = null
        val previous = launch
        launch = null
        launch = null
        val pid = ended?.pid ?: 0
        ended?.close()
        killSession(pid)
        sessionJob?.cancel()
        sessionJob = null
        mutableState.update { it.copy(running = false, starting = false, ended = true,
            error = null, exitCode = exitCode, pid = 0) }
        previous?.managedId?.let { onNaturalExit(it, exitCode) }
        val identity = previous?.workspaceIdentity
        if (!identity.isNullOrEmpty()) scope.launch { onSync(identity) }
    }

    private fun closeInternal(markEnded: Boolean) {
        if (finished) return
        finished = true
        requested = false
        starting = false
        generation++
        watcherJob?.cancel()
        watcherJob = null
        resizeJob?.cancel()
        resizeJob = null
        val ended = engine
        engine = null
        val previous = launch
        launch = null
        val pid = ended?.pid ?: 0
        ended?.close()
        killSession(pid)
        val job = sessionJob
        sessionJob = null
        job?.cancel()
        mutableState.update { it.copy(running = false, starting = false,
            ended = markEnded, exitCode = null, pid = 0) }
        previous?.managedId?.let(onClosed)
        val identity = previous?.workspaceIdentity
        if (!identity.isNullOrEmpty() && markEnded) scope.launch { onSync(identity) }
    }

    private fun killSession(pid: Int) {
        if (pid > 0) killGroup(pid)
    }
}

private object DefaultTerminalTreeWatcher : TerminalTreeWatcher {
    override suspend fun watch(pid: Int, memoryCapBytes: Long?,
                              terminate: suspend () -> Unit): String? =
        when (watchCommandTree(pid, memoryCapBytes, { _ -> }, {}, {}, terminate)) {
            TreeWatchStop.TooManyChildren -> "too many child processes"
            TreeWatchStop.MemoryLimit -> "memory limit"
        }
}

internal object PtyTerminalEngineFactory : TerminalEngineFactory {
    override fun create(launch: TerminalLaunch, observer: TerminalObserver): TerminalEngine =
        PtyTerminalEngine(launch, observer)
}

/** The real PTY-backed engine; TerminalSession owns its reader/writer/waiter threads. */
internal class PtyTerminalEngine(
    launch: TerminalLaunch,
    private val observer: TerminalObserver,
) : TerminalEngine {
    private val client = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) = observer.changed()
        override fun onTitleChanged(changedSession: TerminalSession) = observer.changed()
        override fun onSessionFinished(finishedSession: TerminalSession) =
            observer.exited(finishedSession.exitStatus)
        override fun onCopyTextToClipboard(session: TerminalSession, text: String) = Unit
        override fun onPasteTextFromClipboard(session: TerminalSession?) = Unit
        override fun onBell(session: TerminalSession) = Unit
        override fun onColorsChanged(session: TerminalSession) = observer.changed()
        override fun onTerminalCursorStateChange(state: Boolean) = observer.changed()
        override fun setTerminalShellPid(session: TerminalSession, pid: Int) = Unit
        override fun getTerminalCursorStyle(): Int =
            TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK
        override fun logError(tag: String, message: String) { Log.e(tag, message) }
        override fun logWarn(tag: String, message: String) { Log.w(tag, message) }
        override fun logInfo(tag: String, message: String) { Log.i(tag, message) }
        override fun logDebug(tag: String, message: String) { Log.d(tag, message) }
        override fun logVerbose(tag: String, message: String) { Log.v(tag, message) }
        override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {
            Log.e(tag, message, e)
        }
        override fun logStackTrace(tag: String, e: Exception) {
            Log.e(tag, "terminal", e)
        }
    }

    private val session = TerminalSession(
        launch.shellPath,
        launch.cwd,
        arrayOf(launch.shellPath),
        launch.environment,
        2000,
        client,
    )

    override val pid: Int get() = session.pid
    override val emulator: TerminalEmulator? get() = session.emulator

    override fun write(text: String) {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        session.write(bytes, 0, bytes.size)
    }

    override fun resize(columns: Int, rows: Int, cellWidthPx: Int, cellHeightPx: Int) {
        session.updateSize(columns, rows, cellWidthPx, cellHeightPx)
    }

    override fun close() {
        val pidNow = session.pid
        session.finishIfRunning()
        if (pidNow > 0) ProcTree.sessionMembers(pidNow).forEach(ProcTree::kill)
    }
}
