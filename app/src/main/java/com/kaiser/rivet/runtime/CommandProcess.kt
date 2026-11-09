package com.kaiser.rivet.runtime

import android.os.ParcelFileDescriptor
import android.system.OsConstants
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Rule 6 poll cadence: one /proc walk every two seconds (plan §5). The first walk is
 * early so a command that forks a burst and exits is still seen. */
private const val TREE_POLL_MS = 2_000L
private const val FIRST_TREE_POLL_MS = 250L

internal enum class TreeWatchStop { TooManyChildren, MemoryLimit }

/** The rule 6/7 watchdog, shared by every command path (agent, terminal panel). */
internal suspend fun watchCommandTree(
    root: Int,
    memoryCapBytes: Long?,
    onSample: (Set<Int>) -> Unit,
    onTooManyChildren: () -> Unit,
    onMemoryLimit: () -> Unit,
    terminate: suspend () -> Unit,
): TreeWatchStop {
    var memorySamples = 0
    var first = true
    while (true) {
        delay(if (first) FIRST_TREE_POLL_MS else TREE_POLL_MS)
        first = false
        val tree = try { descendants(root, ProcTree.parents()) }
            catch (_: Exception) { emptySet<Int>() }
        if (tree.isNotEmpty()) onSample(tree)
        if (tree.size > MAX_DESCENDANTS) {
            onTooManyChildren()
            terminate()
            return TreeWatchStop.TooManyChildren
        }
        if (memoryCapBytes != null) {
            // Rule 7: the leader is part of its tree, so its PSS counts too.
            val pids = tree + root
            val readings = HashMap<Int, Long>()
            for (member in pids) ProcTree.pssKb(member)?.let { readings[member] = it }
            memorySamples = memoryBreachSamples(memorySamples,
                pssBytes(readings, pids), memoryCapBytes)
            if (memoryLimitReached(memorySamples)) {
                onMemoryLimit()
                terminate()
                return TreeWatchStop.MemoryLimit
            }
        }
    }
}

internal object CommandNative {
    init { System.loadLibrary("termux") }
    external fun start(command: String, cwd: String, environment: Array<String>): IntArray
    external fun waitFor(pid: Int): Int
    external fun signalGroup(pid: Int, signal: Int)
    external fun signalLeader(pid: Int, signal: Int)
    external fun closeFd(fd: Int)
}

internal data class CommandOutput(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val stdoutTruncated: Boolean,
    val stderrTruncated: Boolean,
    val timedOut: Boolean,
    val tooManyChildren: Boolean = false,
    val memoryLimit: Boolean = false,
)

internal data class CommandExitOutcome(
    val exitCode: Int,
    val timedOut: Boolean,
)

internal suspend fun awaitExitOrTimeout(
    exited: CompletableDeferred<Int>,
    timeoutMs: Long,
    onTimeout: suspend () -> Int,
): CommandExitOutcome {
    val completed = withTimeoutOrNull(timeoutMs) { exited.await() }
    return when {
        completed != null -> CommandExitOutcome(completed, timedOut = false)
        exited.isCompleted -> CommandExitOutcome(exited.await(), timedOut = false)
        else -> CommandExitOutcome(onTimeout(), timedOut = true)
    }
}

internal class CommandProcess {
    suspend fun run(command: String, cwd: String, environment: Array<String>, timeoutMs: Long,
                    onOutput: ((String, String) -> Unit)? = null,
                    memoryCapBytes: Long? = null): CommandOutput =
        coroutineScope {
            val started = CommandNative.start(command, cwd, environment)
            check(started.size == 3 && started[0] > 0)
            val pid = started[0]
            val stdoutFd = try { ParcelFileDescriptor.adoptFd(started[1]) }
                catch (e: Exception) {
                    CommandNative.signalGroup(pid, OsConstants.SIGKILL)
                    CommandNative.signalLeader(pid, OsConstants.SIGKILL)
                    try { CommandNative.waitFor(pid) } catch (_: Exception) { }
                    CommandNative.closeFd(started[1])
                    CommandNative.closeFd(started[2])
                    throw e
                }
            val stderrFd = try { ParcelFileDescriptor.adoptFd(started[2]) }
                catch (e: Exception) {
                    CommandNative.signalGroup(pid, OsConstants.SIGKILL)
                    CommandNative.signalLeader(pid, OsConstants.SIGKILL)
                    try { CommandNative.waitFor(pid) } catch (_: Exception) { }
                    stdoutFd.close()
                    CommandNative.closeFd(started[2])
                    throw e
                }
            val stdout = BoundedCapture()
            val stderr = BoundedCapture()
            val stdoutReader = async(Dispatchers.IO) { collect(stdoutFd, stdout) }
            val stderrReader = async(Dispatchers.IO) { collect(stderrFd, stderr) }
            val exited = CompletableDeferred<Int>()
            Thread({
                try { exited.complete(CommandNative.waitFor(pid)) }
                catch (e: Exception) { exited.completeExceptionally(e) }
            }, "RivetCommandWait-$pid").apply { isDaemon = true }.start()
            val outputPublisher = onOutput?.let { publish -> launch(Dispatchers.Default) {
                while (true) {
                    delay(250)
                    try { publish(stdout.text(), stderr.text()) }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { /* Display failures do not interrupt the command. */ }
                }
            } }
            // Rules 5/6 bookkeeping: every pid seen under this command, with its start
            // time so a reused pid is never swept by mistake.
            val strays = ConcurrentHashMap<Int, Long>()
            val tooManyChildren = AtomicBoolean(false)
            val memoryLimit = AtomicBoolean(false)
            fun remember(tree: Set<Int>) {
                for (child in tree) {
                    if (strays.containsKey(child)) continue
                    ProcTree.startTime(child)?.let { strays.putIfAbsent(child, it) }
                }
            }
            fun rememberCurrentTree() {
                try { remember(descendants(pid, ProcTree.parents())) } catch (_: Exception) { }
            }
            fun sweepStrays() {
                for ((child, started) in strays) {
                    if (ProcTree.startTime(child) == started) ProcTree.kill(child)
                }
            }
            // The watcher samples every 2 s; a burst that exits before the first sample
            // is still counted here, and the whole session is ended in one place.
            fun endSession() {
                val members = ProcTree.sessionMembers(pid)
                if (overSessionCap(pid, members)) tooManyChildren.set(true)
                for (member in members) ProcTree.kill(member)
            }
            val treeWatch = launch(Dispatchers.IO) {
                watchCommandTree(
                    root = pid,
                    memoryCapBytes = memoryCapBytes,
                    onSample = { tree -> remember(tree) },
                    onTooManyChildren = { tooManyChildren.set(true) },
                    onMemoryLimit = { memoryLimit.set(true) },
                    terminate = { terminate(pid, exited) },
                )
            }
            try {
                val outcome = awaitExitOrTimeout(exited, timeoutMs) {
                    rememberCurrentTree()
                    terminate(pid, exited)
                }
                val exitCode = outcome.exitCode
                val timedOut = outcome.timedOut
                treeWatch.cancel()
                // The foreground shell may have exited while children kept
                // inherited output pipes open. End that process group first, then its
                // session (background jobs can move to their own process group).
                CommandNative.signalGroup(pid, OsConstants.SIGKILL)
                endSession()
                // Rule 5: children that left the group (setsid) are not reached by that
                // kill; sweep the strays the watcher saw. Best effort: a process that
                // forked, setsid'd and lost its parent between two samples is invisible
                // here (ponytail: 2 s sampling; a UID-wide turn-end sweep is the upgrade).
                sweepStrays()
                if (withTimeoutOrNull(3000) { joinAll(stdoutReader, stderrReader) } == null) {
                    stdoutFd.close()
                    stderrFd.close()
                    stdoutReader.cancel()
                    stderrReader.cancel()
                }
                CommandOutput(exitCode, stdout.text(), stderr.text(), stdout.truncated,
                    stderr.truncated, timedOut, tooManyChildren.get(), memoryLimit.get())
            } catch (e: CancellationException) {
                withContext(NonCancellable) {
                    rememberCurrentTree()
                    terminate(pid, exited)
                    endSession()
                    stdoutFd.close()
                    stderrFd.close()
                    withTimeoutOrNull(3000) { joinAll(stdoutReader, stderrReader) }
                }
                throw e
            } finally {
                treeWatch.cancel()
                outputPublisher?.cancel()
                CommandNative.signalGroup(pid, OsConstants.SIGKILL)
                endSession()
                sweepStrays()
                stdoutFd.close()
                stderrFd.close()
                stdoutReader.cancel()
                stderrReader.cancel()
            }
        }

    private suspend fun terminate(pid: Int, exited: CompletableDeferred<Int>): Int {
        CommandNative.signalGroup(pid, OsConstants.SIGTERM)
        if (!exited.isCompleted) CommandNative.signalLeader(pid, OsConstants.SIGTERM)
        val graceful = withTimeoutOrNull(750) { exited.await() }
        CommandNative.signalGroup(pid, OsConstants.SIGKILL)
        if (!exited.isCompleted) CommandNative.signalLeader(pid, OsConstants.SIGKILL)
        return graceful ?: withTimeoutOrNull(3000) { exited.await() } ?: -OsConstants.SIGKILL
    }

    private fun collect(descriptor: ParcelFileDescriptor, output: BoundedCapture) {
        try {
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                val buffer = ByteArray(4096)
                while (true) {
                    val size = input.read(buffer)
                    if (size < 0) break
                    output.append(buffer, size)
                }
            }
        } catch (_: IOException) { /* Closing the descriptor interrupts collection. */ }
    }
}

/** Bounded head and tail retain useful diagnostics without retaining whole logs. */
internal class BoundedCapture(private val half: Int = 4096) {
    private val head = ByteArray(half)
    private val tail = ByteArray(half)
    private var headSize = 0
    private var tailSize = 0
    private var tailNext = 0
    private var total = 0L
    val truncated: Boolean get() = total > half * 2L

    @Synchronized fun append(bytes: ByteArray, size: Int) {
        for (index in 0 until size) {
            val value = bytes[index]
            if (headSize < half) head[headSize++] = value
            else {
                tail[tailNext] = value
                tailNext = (tailNext + 1) % half
                if (tailSize < half) tailSize++
            }
            total++
        }
    }

    @Synchronized fun text(): String {
        val first = head.copyOf(headSize).toString(Charsets.UTF_8)
        if (tailSize == 0) return first
        val start = if (tailSize == half) tailNext else 0
        val last = ByteArray(tailSize) { tail[(start + it) % half] }.toString(Charsets.UTF_8)
        return if (truncated) "$first\n… [output truncated] …\n$last" else first + last
    }
}
