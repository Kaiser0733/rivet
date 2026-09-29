package com.kaiser.rivet.runtime

import android.content.Context
import android.content.Intent
import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

enum class ManagedProcessKind { Command, Preview }
enum class ManagedProcessStatus { Starting, Running, Stopping, Completed, Failed, Stopped }

data class ManagedProcessInfo(
    val id: String,
    val kind: ManagedProcessKind,
    val projectName: String,
    val label: String,
    val status: ManagedProcessStatus,
    val startedAtMillis: Long,
    val url: String? = null,
    val output: String = "",
    val exitCode: Int? = null,
)

/** App-process ownership for live commands and loopback previews. */
class ManagedProcesses {
    private data class Entry(
        var info: ManagedProcessInfo,
        val workspaceIdentity: String,
        val persistent: Boolean,
        val stop: () -> Unit,
    )

    private val lock = Any()
    private val serviceMutex = Mutex()
    private val entries = linkedMapOf<String, Entry>()
    private var serviceStarted = false
    private var serviceWaiter: CompletableDeferred<Boolean>? = null
    private val mutableProcesses = MutableStateFlow<List<ManagedProcessInfo>>(emptyList())
    val processes: StateFlow<List<ManagedProcessInfo>> = mutableProcesses.asStateFlow()

    fun registerCommand(
        command: String,
        cwd: String,
        workspaceIdentity: String,
        projectName: String?,
        job: Job,
    ): String? = synchronized(lock) {
        if (entries.values.count { it.info.status in ACTIVE && it.persistent } >= MAX_PERSISTENT_PROCESSES ||
            entries.values.count { it.info.status in ACTIVE } >= MAX_ACTIVE_PROCESSES) return null
        val id = UUID.randomUUID().toString()
        val safeCommand = redactCommand(command)
        val suffix = cwd.takeIf(String::isNotEmpty)?.let { " · $it" }.orEmpty()
        val entry = Entry(
            ManagedProcessInfo(id, ManagedProcessKind.Command, projectName?.take(100) ?: "Selected project",
                (safeCommand + suffix).take(MAX_LABEL_CHARS), ManagedProcessStatus.Running,
                System.currentTimeMillis()),
            workspaceIdentity,
            persistent = false,
        ) { job.cancel(CancellationException("Stopped from Processes")) }
        entries[id] = entry
        trimCompletedLocked()
        publishLocked()
        id
    }

    fun finishCommand(id: String, result: RuntimeCommandResult) = synchronized(lock) {
        val entry = entries[id] ?: return@synchronized
        val status = when {
            entry.info.status == ManagedProcessStatus.Stopping -> ManagedProcessStatus.Stopped
            result.error != null || result.exitCode != 0 -> ManagedProcessStatus.Failed
            else -> ManagedProcessStatus.Completed
        }
        entry.info = entry.info.copy(status = status, exitCode = result.exitCode,
            output = boundedOutput(result.stdout, result.stderr))
        trimCompletedLocked()
        publishLocked()
    }

    fun failCommand(id: String) = synchronized(lock) {
        entries[id]?.let { entry ->
            if (entry.info.status == ManagedProcessStatus.Stopping) {
                entry.info = entry.info.copy(status = ManagedProcessStatus.Stopped)
            } else entry.info = entry.info.copy(status = ManagedProcessStatus.Failed)
            trimCompletedLocked()
            publishLocked()
        }
    }

    fun canStartPersistent(): Boolean = synchronized(lock) {
        entries.values.count { it.persistent && it.info.status in ACTIVE } < MAX_PERSISTENT_PROCESSES
    }

    fun canStartPreview(): Boolean = synchronized(lock) {
        canStartPersistentLocked() && entries.values.count { it.info.status in ACTIVE } < MAX_ACTIVE_PROCESSES
    }

    fun canStartCommand(): Boolean = synchronized(lock) {
        entries.values.count { it.info.status in ACTIVE } < MAX_ACTIVE_PROCESSES
    }

    fun stopDescription(id: String): String? = synchronized(lock) {
        val info = entries[id]?.info?.takeIf { it.status in ACTIVE } ?: return@synchronized null
        if (info.kind == ManagedProcessKind.Preview) "Local preview for ${info.projectName}"
        else "Project command for ${info.projectName}"
    }

    fun activeForWorkspace(identity: String?): List<ManagedProcessInfo> = synchronized(lock) {
        if (identity == null) return@synchronized emptyList()
        entries.values.filter { it.workspaceIdentity == identity && it.info.status in ACTIVE }
            .map { it.info }
    }

    fun stopDescription(id: String, workspaceIdentity: String): String? = synchronized(lock) {
        val entry = entries[id]?.takeIf { it.workspaceIdentity == workspaceIdentity }
            ?: return@synchronized null
        entry.info.takeIf { it.status in ACTIVE }?.let {
            if (it.kind == ManagedProcessKind.Preview) "Local preview for ${it.projectName}"
            else "Project command for ${it.projectName}"
        }
    }

    fun stop(id: String, workspaceIdentity: String): Boolean {
        if (!ownsWorkspace(id, workspaceIdentity)) return false
        return stop(id)
    }

    internal fun registerPreview(
        workspaceIdentity: String,
        projectName: String?,
        entryPath: String,
        url: String,
        stop: () -> Unit,
    ): String? = synchronized(lock) {
        if (!canStartPersistentLocked() || entries.values.count { it.info.status in ACTIVE } >= MAX_ACTIVE_PROCESSES) return null
        val id = UUID.randomUUID().toString()
        entries[id] = Entry(
            ManagedProcessInfo(id, ManagedProcessKind.Preview,
                projectName?.take(100) ?: "Selected project", entryPath.take(MAX_LABEL_CHARS),
                ManagedProcessStatus.Starting, System.currentTimeMillis(), url = url.take(160)),
            workspaceIdentity,
            persistent = true,
            stop = stop,
        )
        trimCompletedLocked()
        publishLocked()
        id
    }

    internal fun markPreviewRunning(id: String) = synchronized(lock) {
        entries[id]?.let { entry ->
            if (entry.info.status == ManagedProcessStatus.Starting) {
                entry.info = entry.info.copy(status = ManagedProcessStatus.Running)
                publishLocked()
            }
        }
    }

    fun stop(id: String): Boolean {
        val stopAndKind = synchronized(lock) {
            val entry = entries[id] ?: return false
            if (entry.info.status !in setOf(ManagedProcessStatus.Starting, ManagedProcessStatus.Running)) return false
            entry.info = entry.info.copy(status = ManagedProcessStatus.Stopping)
            publishLocked()
            entry.stop to entry.persistent
        }
        return try {
            stopAndKind.first()
            if (stopAndKind.second) finishPreview(id)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    fun stopAll() {
        val active = synchronized(lock) {
            entries.entries.filter { it.value.info.status in setOf(
                ManagedProcessStatus.Starting, ManagedProcessStatus.Running,
            ) }.map { (id, entry) ->
                entry.info = entry.info.copy(status = ManagedProcessStatus.Stopping)
                Triple(id, entry.stop, entry.persistent)
            }.also { publishLocked() }
        }
        active.forEach { (id, stop, persistent) ->
            try {
                stop()
                if (persistent) finishPreview(id)
            }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Other owned processes still need their stop signal. */ }
        }
    }

    fun stopPersistent() {
        val active = synchronized(lock) {
            entries.entries.filter { it.value.persistent && it.value.info.status in setOf(
                ManagedProcessStatus.Starting, ManagedProcessStatus.Running,
            ) }.map { (id, entry) ->
                entry.info = entry.info.copy(status = ManagedProcessStatus.Stopping)
                id to entry.stop
            }.also { publishLocked() }
        }
        active.forEach { (id, stop) ->
            try { stop(); finishPreview(id) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { finishPreview(id, failed = true) }
        }
    }

    suspend fun ensureForegroundService(context: Context): Boolean = serviceMutex.withLock {
        if (synchronized(lock) { serviceStarted }) return@withLock true
        val waiter = CompletableDeferred<Boolean>()
        synchronized(lock) { serviceWaiter = waiter }
        try {
            val intent = Intent(context.applicationContext, PersistentProcessService::class.java)
                .setAction(PersistentProcessService.ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        } catch (e: CancellationException) {
            synchronized(lock) { if (serviceWaiter === waiter) serviceWaiter = null }
            throw e
        } catch (_: Exception) {
            synchronized(lock) { if (serviceWaiter === waiter) serviceWaiter = null }
            return@withLock false
        }
        val started = withTimeoutOrNull(SERVICE_START_TIMEOUT_MS) { waiter.await() } ?: false
        synchronized(lock) {
            if (serviceWaiter === waiter) serviceWaiter = null
            serviceStarted = started
        }
        started
    }

    internal fun onForegroundServiceStarted(started: Boolean) = synchronized(lock) {
        serviceStarted = started
        serviceWaiter?.complete(started)
    }

    internal fun onForegroundServiceStopped() = synchronized(lock) {
        serviceStarted = false
    }

    internal fun activePersistentCount(): Int = synchronized(lock) {
        entries.values.count { it.persistent && it.info.status in ACTIVE }
    }

    internal fun finishPreview(id: String, failed: Boolean = false) = synchronized(lock) {
        val entry = entries[id] ?: return@synchronized
        entry.info = entry.info.copy(status = when {
            failed -> ManagedProcessStatus.Failed
            entry.info.status == ManagedProcessStatus.Stopping -> ManagedProcessStatus.Stopped
            else -> ManagedProcessStatus.Stopped
        })
        trimCompletedLocked()
        publishLocked()
    }

    internal fun ownsWorkspace(id: String, identity: String): Boolean = synchronized(lock) {
        entries[id]?.workspaceIdentity == identity
    }

    private fun canStartPersistentLocked() =
        entries.values.count { it.persistent && it.info.status in ACTIVE } < MAX_PERSISTENT_PROCESSES

    private fun publishLocked() {
        mutableProcesses.value = entries.values.map { it.info }.sortedByDescending { it.startedAtMillis }
    }

    private fun trimCompletedLocked() {
        val completed = entries.values.filter { it.info.status !in ACTIVE }
            .sortedByDescending { it.info.startedAtMillis }
        completed.drop(MAX_COMPLETED_RECORDS).forEach { entries.remove(it.info.id) }
    }

    private fun boundedOutput(stdout: String, stderr: String): String {
        val combined = buildString {
            if (stdout.isNotBlank()) append(stdout)
            if (stderr.isNotBlank()) {
                if (isNotEmpty()) append('\n')
                append(stderr)
            }
        }
        return combined.takeLast(MAX_OUTPUT_CHARS)
            .dropWhile(Char::isLowSurrogate).dropLastWhile(Char::isHighSurrogate)
            .replace(Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]"), "�")
    }

    private fun redactCommand(command: String): String = command
        .replace(Regex("(?i)(api[_-]?key|token|password|secret)(\\s*[:=]\\s*)[^\\s]+"), "$1$2[redacted]")
        .replace(Regex("(?i)(https?://)[^/@\\s]+@"), "$1[redacted]@")
        .replace(Regex("(?i)(?:--user(?:name)?|--password)(\\s+)[^\\s]+"), "$1[redacted]")
        .replace(Regex("(?i)(?:^|\\s)-u(\\s+)[^\\s]+"), " -u$1[redacted]")
        .replace(Regex("(?i)(authorization\\s*[:=]\\s*)(?:bearer\\s+)?[^\\s]+"), "$1[redacted]")
        .replace(Regex("[\\r\\n\\u0000-\\u001f]"), " ")

    companion object {
        const val MAX_PERSISTENT_PROCESSES = 4
        const val MAX_ACTIVE_PROCESSES = 4
        const val MAX_OUTPUT_CHARS = 8 * 1024
        const val MAX_LABEL_CHARS = 512
        private const val MAX_COMPLETED_RECORDS = 8
        private const val SERVICE_START_TIMEOUT_MS = 5_000L
        private val ACTIVE = setOf(ManagedProcessStatus.Starting, ManagedProcessStatus.Running,
            ManagedProcessStatus.Stopping)
    }
}
