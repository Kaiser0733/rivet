package com.kaiser.rivet.runtime

import android.content.Context
import android.content.Intent
import android.os.Build
import com.kaiser.rivet.RivetApplication
import com.kaiser.rivet.agent.AgentApprovalRequest
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
import java.util.WeakHashMap

enum class ManagedProcessKind { Command, Preview }
enum class ManagedProcessStatus { Starting, Running, Stopping, Completed, Failed, Stopped }

internal data class ForegroundServiceState(
    val activeTurns: Int = 0,
    val activePreviews: Int = 0,
    val runTitle: String? = null,
    val runAction: String? = null,
    val runStartedAt: Long = 0L,
    val approvalTitle: String? = null,
    val approvalToken: Long? = null,
    val approvalDangerous: Boolean = false,
) {
    val hasActiveWork: Boolean get() = activeTurns > 0 || activePreviews > 0
}

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
    val finishedAtMillis: Long? = null,
)

/** App-process ownership for live commands, loopback previews, and active agent turns. */
class ManagedProcesses {
    private data class Entry(
        var info: ManagedProcessInfo,
        val workspaceIdentity: String,
        val persistent: Boolean,
        val stop: () -> Unit,
    )

    private val lock = Any()
    private val serviceMutex = Mutex()
    // Rule 1's budget, lowered to 1 while the phone is thermally SERIOUS (plan §5 rule 12).
    // Lowering never stops running work; it only refuses new starts.
    @Volatile private var activeLimit = MAX_ACTIVE_PROCESSES

    private val entries = linkedMapOf<String, Entry>()
    private val activeTurns = linkedMapOf<String, () -> Unit>()
    private var serviceStarted = false
    private var serviceWaiter: CompletableDeferred<Boolean>? = null
    private var runTitle: String? = null
    private var runAction: String? = null
    private var runStartedAt = 0L
    private var approvalTitle: String? = null
    private var approvalToken: Long? = null
    private var approvalDangerous = false
    private val mutableProcesses = MutableStateFlow<List<ManagedProcessInfo>>(emptyList())
    val processes: StateFlow<List<ManagedProcessInfo>> = mutableProcesses.asStateFlow()
    private val mutableForegroundState = MutableStateFlow(ForegroundServiceState())
    internal val foregroundState: StateFlow<ForegroundServiceState> = mutableForegroundState.asStateFlow()

    fun registerCommand(
        command: String,
        cwd: String,
        workspaceIdentity: String,
        projectName: String?,
        job: Job,
    ): String? = synchronized(lock) {
        if (entries.values.count { it.info.status in ACTIVE && it.persistent } >= MAX_PERSISTENT_PROCESSES ||
            entries.values.count { it.info.status in ACTIVE } >= activeLimit) return null
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
            output = boundedOutput(result.stdout, result.stderr),
            finishedAtMillis = entry.info.finishedAtMillis ?: System.currentTimeMillis())
        trimCompletedLocked()
        publishLocked()
    }

    internal fun updateCommandOutput(id: String, stdout: String, stderr: String) = synchronized(lock) {
        val entry = entries[id]?.takeIf {
            it.info.kind == ManagedProcessKind.Command && it.info.status == ManagedProcessStatus.Running
        } ?: return@synchronized
        entry.info = entry.info.copy(output = boundedOutput(stdout, stderr))
        publishLocked()
    }

    fun failCommand(id: String) = synchronized(lock) {
        entries[id]?.let { entry ->
            if (entry.info.status == ManagedProcessStatus.Stopping) {
                entry.info = entry.info.copy(status = ManagedProcessStatus.Stopped,
                    finishedAtMillis = entry.info.finishedAtMillis ?: System.currentTimeMillis())
            } else entry.info = entry.info.copy(status = ManagedProcessStatus.Failed,
                finishedAtMillis = entry.info.finishedAtMillis ?: System.currentTimeMillis())
            trimCompletedLocked()
            publishLocked()
        }
    }

    fun canStartPersistent(): Boolean = synchronized(lock) {
        entries.values.count { it.persistent && it.info.status in ACTIVE } < MAX_PERSISTENT_PROCESSES
    }

    fun canStartPreview(): Boolean = synchronized(lock) {
        canStartPersistentLocked() && entries.values.count { it.info.status in ACTIVE } < activeLimit
    }

    fun canStartCommand(): Boolean = synchronized(lock) {
        entries.values.count { it.info.status in ACTIVE } < activeLimit
    }

    internal fun setActiveLimit(limit: Int) {
        activeLimit = limit.coerceIn(1, MAX_ACTIVE_PROCESSES)
    }

    /** Active commands and previews, for the one-line terminal state. */
    fun activeCount(): Int = synchronized(lock) {
        entries.values.count { it.info.status in ACTIVE }
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
        if (!canStartPersistentLocked() || entries.values.count { it.info.status in ACTIVE } >= activeLimit) return null
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
        val (active, turnStops) = synchronized(lock) {
            val activeEntries = entries.entries.filter { it.value.info.status in setOf(
                ManagedProcessStatus.Starting, ManagedProcessStatus.Running,
            ) }.map { (id, entry) ->
                entry.info = entry.info.copy(status = ManagedProcessStatus.Stopping)
                Triple(id, entry.stop, entry.persistent)
            }
            val turns = activeTurns.values.toList()
            activeTurns.clear()
            publishLocked()
            activeEntries to turns
        }
        turnStops.forEach { stop ->
            try { stop() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Other owned processes still need their stop signal. */ }
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

    fun startTurn(context: Context? = null, stop: () -> Unit = {}): String {
        val (id, shouldStartService) = synchronized(lock) {
            val id = UUID.randomUUID().toString()
            activeTurns[id] = stop
            // Start the clock as soon as the turn exists. updateRunStatus normally
            // supplies the run's own timestamp a moment later, but a notification
            // must never lose elapsed time if that update is delayed or skipped.
            if (runStartedAt == 0L) runStartedAt = System.currentTimeMillis()
            publishLocked()
            id to !serviceStarted
        }
        if (shouldStartService && context != null) {
            try {
                val appContext = context.applicationContext ?: context
                val intent = Intent(appContext, PersistentProcessService::class.java)
                    .setAction(PersistentProcessService.ACTION_START)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) appContext.startForegroundService(intent)
                else appContext.startService(intent)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // On Android 12+, starting a foreground service while backgrounded may be rejected;
                // the turn still runs in-process.
            }
        }
        return id
    }

    fun finishTurn(id: String) = synchronized(lock) {
        if (activeTurns.remove(id) != null) {
            if (activeTurns.isEmpty()) {
                runTitle = null
                runAction = null
                runStartedAt = 0L
                approvalTitle = null
                approvalToken = null
                approvalDangerous = false
            }
            publishLocked()
        }
    }

    /** The persistent notification's public face while a run is active. */
    fun updateRunStatus(title: String?, action: String?, startedAt: Long = 0L) =
        synchronized(lock) {
            runTitle = title?.take(160)
            runAction = action?.take(200)
            runStartedAt = if (startedAt > 0) startedAt else runStartedAt
            publishLocked()
        }

    fun updateApproval(request: AgentApprovalRequest?) = synchronized(lock) {
        approvalTitle = request?.title?.take(160)
        approvalToken = request?.approvalToken
        approvalDangerous = request?.dangerous == true
        publishLocked()
    }

    internal fun activeTurnCount(): Int = synchronized(lock) {
        activeTurns.size
    }

    internal fun hasForegroundWork(): Boolean = synchronized(lock) {
        hasForegroundWorkLocked()
    }

    internal fun stopForegroundServiceIfIdle(): Boolean = synchronized(lock) {
        if (hasForegroundWorkLocked()) false
        else {
            serviceStarted = false
            true
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
        activePersistentCountLocked()
    }

    internal fun finishPreview(id: String, failed: Boolean = false) = synchronized(lock) {
        val entry = entries[id] ?: return@synchronized
        entry.info = entry.info.copy(status = when {
            failed -> ManagedProcessStatus.Failed
            entry.info.status == ManagedProcessStatus.Stopping -> ManagedProcessStatus.Stopped
            else -> ManagedProcessStatus.Stopped
        }, finishedAtMillis = entry.info.finishedAtMillis ?: System.currentTimeMillis())
        trimCompletedLocked()
        publishLocked()
    }

    internal fun ownsWorkspace(id: String, identity: String): Boolean = synchronized(lock) {
        entries[id]?.workspaceIdentity == identity
    }

    private fun activePersistentCountLocked() =
        entries.values.count { it.persistent && it.info.status in ACTIVE }

    private fun hasForegroundWorkLocked() =
        activeTurns.isNotEmpty() || activePersistentCountLocked() > 0

    private fun canStartPersistentLocked() =
        activePersistentCountLocked() < MAX_PERSISTENT_PROCESSES

    private fun publishLocked() {
        mutableProcesses.value = entries.values.map { it.info }.sortedByDescending { it.startedAtMillis }
        mutableForegroundState.value = ForegroundServiceState(
            activeTurns = activeTurns.size,
            activePreviews = activePersistentCountLocked(),
            runTitle = runTitle,
            runAction = runAction,
            runStartedAt = runStartedAt,
            approvalTitle = approvalTitle,
            approvalToken = approvalToken,
            approvalDangerous = approvalDangerous,
        )
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

    private fun redactCommand(command: String): String = CommandRedactor.redact(command)

    companion object {
        const val MAX_PERSISTENT_PROCESSES = 4
        const val MAX_ACTIVE_PROCESSES = 4
        const val MAX_OUTPUT_CHARS = 8 * 1024
        const val MAX_LABEL_CHARS = 512
        private const val MAX_COMPLETED_RECORDS = 8
        private const val SERVICE_START_TIMEOUT_MS = 5_000L
        private val ACTIVE = setOf(ManagedProcessStatus.Starting, ManagedProcessStatus.Running,
            ManagedProcessStatus.Stopping)
        private val fallbackByApp = WeakHashMap<Context, ManagedProcesses>()

        fun forApplication(context: Context): ManagedProcesses {
            val app = context.applicationContext ?: context
            if (app is RivetApplication) return app.managedProcesses
            return synchronized(fallbackByApp) {
                fallbackByApp.getOrPut(app) { ManagedProcesses() }
            }
        }
    }
}
