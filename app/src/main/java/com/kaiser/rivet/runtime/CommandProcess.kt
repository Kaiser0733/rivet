package com.kaiser.rivet.runtime

import android.os.ParcelFileDescriptor
import android.system.OsConstants
import java.io.IOException
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

/** Rule 6 poll cadence. The first walk is immediate so a command that forks a burst
 * and exits is still seen, and later walks are short enough for the memory cap to
 * stop an allocation loop before system pressure builds. */
// A fast allocation loop can add a gigabyte between two slow samples. The old
// two-second poll saw the tree under the cap once and over it only after the
// device was already reclaiming memory. Keep the loop's own interval short; the
// PSS read is a handful of files for a normal command tree.
private const val TREE_POLL_MS = 100L
private const val FIRST_TREE_POLL_MS = 100L

/** RLIMIT_AS is a backstop, not the PSS comparison. Virtual address space is
 * always larger than PSS/RSS, and threaded interpreters can carry several times
 * their resident size in mappings. Leave headroom so a normal CPython process
 * does not fail before the 100 ms watchdog sees its true PSS. */
internal const val ADDRESS_SPACE_HEADROOM = 3L

/** Zero means no rlimit, matching a missing/zero PSS cap. */
internal fun addressSpaceLimitBytes(memoryCapBytes: Long?): Long =
    memoryCapBytes?.takeIf { it > 0L }?.let { it * ADDRESS_SPACE_HEADROOM } ?: 0L

internal enum class TreeWatchStop { TooManyChildren, MemoryLimit }

/** RLIMIT_AS turns an allocation loop into an ordinary interpreter error on some
 * kernels; treat that as the same governor stop as a watchdog kill. */
internal fun reportsMemoryFailure(stderr: String): Boolean =
    stderr.contains("MemoryError", ignoreCase = true) ||
        stderr.contains("Cannot allocate memory", ignoreCase = true) ||
        stderr.contains("can't allocate memory", ignoreCase = true) ||
        stderr.contains("out of memory", ignoreCase = true)

/** The rule 6/7 watchdog, shared by every command path (agent, terminal panel). */
internal suspend fun watchCommandTree(
    root: Int,
    memoryCapBytes: Long?,
    onSample: (Set<Int>) -> Unit,
    onTooManyChildren: () -> Unit,
    onMemoryLimit: () -> Unit,
    terminate: suspend () -> Unit,
    monitorChildren: Int = 0,
): TreeWatchStop {
    var memorySamples = 0
    var first = true
    while (true) {
        delay(if (first) FIRST_TREE_POLL_MS else TREE_POLL_MS)
        first = false
        val tree = try { descendants(root, ProcTree.parents()) }
            catch (_: Exception) { emptySet<Int>() }
        if (tree.isNotEmpty()) onSample(tree)
        if (tree.size > MAX_DESCENDANTS + monitorChildren) {
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
    external fun start(command: String, cwd: String, environment: Array<String>,
                       addressSpaceLimitBytes: Long): IntArray
    external fun waitFor(pid: Int): Int
    external fun stop(pid: Int, signal: Int)
    external fun reapStaleMonitors()
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
    /** Up to 1 MiB, used only for the on-disk paging spool. */
    val stdoutFull: String = stdout,
    val stderrFull: String = stderr,
    val stdoutFullTruncated: Boolean = false,
    val stderrFullTruncated: Boolean = false,
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
    suspend fun run(command: String, cwd: String, environment: Array<String>, timeoutMs: Long?,
                    onOutput: ((String, String) -> Unit)? = null,
                    memoryCapBytes: Long? = null,
                    onChunk: ((Boolean, ByteArray, Int) -> Unit)? = null): CommandOutput =
        coroutineScope {
            // The PSS watchdog compares the true cap. The rlimit only stops a process
            // that allocates faster than the sampler can run, so it gets 3x headroom
            // for virtual mappings; setting it to the PSS cap false-kills normal
            // threaded interpreters whose VSZ is naturally larger than their RSS.
            val addressSpaceLimit = addressSpaceLimitBytes(memoryCapBytes)
            val started = CommandNative.start(command, cwd, environment, addressSpaceLimit)
            check(started.size == 3 && started[0] > 0)
            val pid = started[0]
            val stdoutFd = try { ParcelFileDescriptor.adoptFd(started[1]) }
                catch (e: Exception) {
                    CommandNative.stop(pid, OsConstants.SIGKILL)
                    try { CommandNative.waitFor(pid) } catch (_: Exception) { }
                    CommandNative.closeFd(started[1])
                    CommandNative.closeFd(started[2])
                    throw e
                }
            val stderrFd = try { ParcelFileDescriptor.adoptFd(started[2]) }
                catch (e: Exception) {
                    CommandNative.stop(pid, OsConstants.SIGKILL)
                    try { CommandNative.waitFor(pid) } catch (_: Exception) { }
                    stdoutFd.close()
                    CommandNative.closeFd(started[2])
                    throw e
                }
            val stdout = BoundedCapture()
            val stderr = BoundedCapture()
            val fullStdout = FullCapture(SPOOL_LIMIT_BYTES)
            val fullStderr = FullCapture(SPOOL_LIMIT_BYTES)
            val stdoutReader = async(Dispatchers.IO) { collect(stdoutFd, stdout, fullStdout) { bytes, count -> onChunk?.invoke(false, bytes, count) } }
            val stderrReader = async(Dispatchers.IO) { collect(stderrFd, stderr, fullStderr) { bytes, count -> onChunk?.invoke(true, bytes, count) } }
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
            val tooManyChildren = AtomicBoolean(false)
            val memoryLimit = AtomicBoolean(false)
            val treeWatch = launch(Dispatchers.IO) {
                watchCommandTree(
                    root = pid,
                    memoryCapBytes = memoryCapBytes,
                    onSample = {},
                    onTooManyChildren = { tooManyChildren.set(true) },
                    onMemoryLimit = { memoryLimit.set(true) },
                    terminate = { terminate(pid, exited) },
                    monitorChildren = 1,
                )
            }
            try {
                val outcome = if (timeoutMs == null) CommandExitOutcome(exited.await(), timedOut = false)
                    else awaitExitOrTimeout(exited, timeoutMs) { terminate(pid, exited) }
                val exitCode = outcome.exitCode
                val timedOut = outcome.timedOut
                treeWatch.cancel()
                if (withTimeoutOrNull(3000) { joinAll(stdoutReader, stderrReader) } == null) {
                    stdoutFd.close()
                    stderrFd.close()
                    stdoutReader.cancel()
                    stderrReader.cancel()
                }
                CommandOutput(exitCode, stdout.text(), stderr.text(), stdout.truncated,
                    stderr.truncated, timedOut, tooManyChildren.get(),
                    memoryLimit.get() || reportsMemoryFailure(stderr.text()),
                    stdoutFull = fullStdout.text(), stderrFull = fullStderr.text(),
                    stdoutFullTruncated = fullStdout.truncated,
                    stderrFullTruncated = fullStderr.truncated)
            } catch (e: CancellationException) {
                withContext(NonCancellable) {
                    terminate(pid, exited)
                    stdoutFd.close()
                    stderrFd.close()
                    withTimeoutOrNull(3000) { joinAll(stdoutReader, stderrReader) }
                }
                throw e
            } finally {
                treeWatch.cancel()
                outputPublisher?.cancel()
                if (!exited.isCompleted) CommandNative.stop(pid, OsConstants.SIGKILL)
                stdoutFd.close()
                stderrFd.close()
                stdoutReader.cancel()
                stderrReader.cancel()
            }
        }

    private suspend fun terminate(pid: Int, exited: CompletableDeferred<Int>): Int {
        if (!exited.isCompleted) CommandNative.stop(pid, OsConstants.SIGTERM)
        val graceful = withTimeoutOrNull(750) { exited.await() }
        if (!exited.isCompleted) CommandNative.stop(pid, OsConstants.SIGKILL)
        return graceful ?: withTimeoutOrNull(3000) { exited.await() } ?: -OsConstants.SIGKILL
    }

    private fun collect(
        descriptor: ParcelFileDescriptor,
        output: BoundedCapture,
        full: FullCapture,
        onChunk: (ByteArray, Int) -> Unit,
    ) {
        try {
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                val buffer = ByteArray(4096)
                while (true) {
                    val size = input.read(buffer)
                    if (size < 0) break
                    output.append(buffer, size)
                    full.append(buffer, size)
                    onChunk(buffer, size)
                }
            }
        } catch (_: IOException) { /* Closing the descriptor interrupts collection. */ }
    }

    private companion object {
        const val SPOOL_LIMIT_BYTES = 1 * 1024 * 1024
    }
}

/** Head-only spool for paging; never exposed as the live tool result. */
internal class FullCapture(private val limit: Int) {
    private val buffer = ByteArray(limit)
    private var size = 0
    var truncated = false
        private set

    @Synchronized fun append(bytes: ByteArray, count: Int) {
        val room = limit - size
        if (count > room) truncated = true
        val take = minOf(room, count)
        if (take > 0) {
            bytes.copyInto(buffer, size, 0, take)
            size += take
        }
    }

    @Synchronized fun text(): String = buffer.copyOf(size).toString(Charsets.UTF_8)
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
