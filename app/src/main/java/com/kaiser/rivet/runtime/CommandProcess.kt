package com.kaiser.rivet.runtime

import android.os.ParcelFileDescriptor
import android.system.OsConstants
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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
)

internal class CommandProcess {
    suspend fun run(command: String, cwd: String, environment: Array<String>, timeoutMs: Long): CommandOutput =
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
            var timedOut = false
            try {
                val completed = withTimeoutOrNull(timeoutMs) { exited.await() }
                val exitCode = if (completed == null) {
                    timedOut = true
                    terminate(pid, exited)
                } else completed
                // The foreground shell may have exited while children kept
                // inherited output pipes open. End that process group first.
                CommandNative.signalGroup(pid, OsConstants.SIGKILL)
                if (withTimeoutOrNull(3000) { joinAll(stdoutReader, stderrReader) } == null) {
                    stdoutFd.close()
                    stderrFd.close()
                    stdoutReader.cancel()
                    stderrReader.cancel()
                }
                CommandOutput(exitCode, stdout.text(), stderr.text(), stdout.truncated,
                    stderr.truncated, timedOut)
            } catch (e: CancellationException) {
                withContext(NonCancellable) {
                    terminate(pid, exited)
                    stdoutFd.close()
                    stderrFd.close()
                    withTimeoutOrNull(3000) { joinAll(stdoutReader, stderrReader) }
                }
                throw e
            } finally {
                CommandNative.signalGroup(pid, OsConstants.SIGKILL)
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
