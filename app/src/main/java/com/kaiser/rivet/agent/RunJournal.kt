package com.kaiser.rivet.agent

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class RunPhase { Queued, Running, Paused, Done, Failed, Stopped }

@Serializable
enum class RunPauseReason(val plain: String) {
    User("paused by you"),
    Approval("waiting for approval"),
    Budget("budget reached — raise or stop"),
    Thermal("device too hot"),
    Battery("battery low"),
    SystemTimeout("paused by the system — tap to continue"),
    NetworkLost("network lost — resumes when connected"),
    AppUpdate("app updated mid-run — resume when ready"),
    Interrupted("interrupted — resume from the last completed step"),
}

@Serializable
data class RunStep(
    val index: Int,
    val at: Long,
    val summary: String,
)

@Serializable
data class RunRecord(
    val id: String,
    val workspaceId: String?,
    val title: String,
    val phase: RunPhase = RunPhase.Queued,
    val pause: RunPauseReason? = null,
    val startedAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val steps: List<RunStep> = emptyList(),
    val lastCompletedStep: Int = 0,
    val failure: String? = null,
) {
    val interrupted: Boolean
        get() = phase == RunPhase.Running || (phase == RunPhase.Paused && pause == RunPauseReason.Interrupted)

    /** Any non-terminal run that can accept Resume, including a user or budget pause. */
    val resumable: Boolean
        get() = phase == RunPhase.Queued || phase == RunPhase.Paused

    fun plainState(): String = when (phase) {
        RunPhase.Queued -> "queued"
        RunPhase.Running -> "running"
        RunPhase.Paused -> pause?.plain ?: "paused"
        RunPhase.Done -> "done"
        RunPhase.Failed -> "failed"
        RunPhase.Stopped -> "stopped"
    }
}

/** Pure transitions so the persisted machine is testable without Android. */
object RunMachine {
    fun begin(id: String, workspaceId: String?, title: String, now: Long = System.currentTimeMillis()):
        RunRecord = RunRecord(id = id, workspaceId = workspaceId, title = title.take(160),
        phase = RunPhase.Queued, startedAt = now, updatedAt = now)

    fun start(run: RunRecord, now: Long = System.currentTimeMillis()): RunRecord = when (run.phase) {
        RunPhase.Queued, RunPhase.Paused -> run.copy(phase = RunPhase.Running, pause = null,
            updatedAt = now)
        else -> throw IllegalStateException("run cannot start from ${run.phase}")
    }

    fun step(run: RunRecord, summary: String, now: Long = System.currentTimeMillis()): RunRecord {
        if (run.phase != RunPhase.Running) throw IllegalStateException("run is not running")
        val index = run.steps.size + 1
        return run.copy(steps = run.steps + RunStep(index, now, summary.take(240)),
            lastCompletedStep = index, updatedAt = now)
    }

    fun pause(run: RunRecord, reason: RunPauseReason, now: Long = System.currentTimeMillis()): RunRecord {
        if (run.phase != RunPhase.Running) throw IllegalStateException("run is not running")
        return run.copy(phase = RunPhase.Paused, pause = reason, updatedAt = now)
    }

    /** Android's dataSync timeout is a pause, never a lost run. */
    fun timeout(run: RunRecord, now: Long = System.currentTimeMillis()): RunRecord =
        if (run.phase == RunPhase.Running) pause(run, RunPauseReason.SystemTimeout, now) else run

    /** Losing the network pauses cloud work; returning connectivity resumes only that pause. */
    fun networkLost(run: RunRecord, now: Long = System.currentTimeMillis()): RunRecord =
        if (run.phase == RunPhase.Running) pause(run, RunPauseReason.NetworkLost, now) else run

    fun networkBack(run: RunRecord, now: Long = System.currentTimeMillis()): RunRecord =
        if (run.phase == RunPhase.Paused && run.pause == RunPauseReason.NetworkLost) resume(run, now)
        else run

    fun resume(run: RunRecord, now: Long = System.currentTimeMillis()): RunRecord {
        if (run.phase != RunPhase.Paused) throw IllegalStateException("run is not paused")
        return run.copy(phase = RunPhase.Running, pause = null, updatedAt = now)
    }

    fun finish(run: RunRecord, phase: RunPhase, failure: String? = null,
               now: Long = System.currentTimeMillis()): RunRecord {
        if (phase !in setOf(RunPhase.Done, RunPhase.Failed, RunPhase.Stopped)) {
            throw IllegalArgumentException("not a terminal phase")
        }
        if (run.phase in setOf(RunPhase.Done, RunPhase.Failed, RunPhase.Stopped)) {
            throw IllegalStateException("run is already finished")
        }
        return run.copy(phase = phase, failure = failure?.take(240), pause = null, updatedAt = now)
    }

    /** A process death turns anything live into an honest, resumable pause. */
    fun recover(run: RunRecord, now: Long = System.currentTimeMillis()): RunRecord =
        if (run.phase == RunPhase.Queued || run.phase == RunPhase.Running) {
            run.copy(phase = RunPhase.Paused, pause = RunPauseReason.Interrupted, updatedAt = now)
        } else if (run.phase == RunPhase.Paused && run.pause == null) {
            run.copy(pause = RunPauseReason.Interrupted, updatedAt = now)
        } else run
}

interface RunStorage {
    suspend fun read(relative: String): String?
    suspend fun write(relative: String, text: String)
    suspend fun directories(relative: String): List<String>
}

/** Local-file storage for tests and for app-private journals. */
class DirectoryRunStorage(private val root: File) : RunStorage {
    override suspend fun read(relative: String): String? {
        val file = File(root, relative)
        return try { if (file.isFile) file.readText() else null } catch (_: Exception) { null }
    }

    override suspend fun write(relative: String, text: String) {
        val file = File(root, relative)
        file.parentFile?.mkdirs()
        file.writeText(text)
    }

    override suspend fun directories(relative: String): List<String> =
        File(root, relative).listFiles()?.filter { it.isDirectory }?.map { it.name }.orEmpty()
}

/** SAF-backed storage for the real project. */
class ProjectRunStorage(private val files: ProjectStateFiles) : RunStorage {
    override suspend fun read(relative: String): String? = files.read(relative)
    override suspend fun write(relative: String, text: String) = files.write(relative, text)
    override suspend fun directories(relative: String): List<String> =
        files.listDirectories(relative).map { it.substringAfterLast('/') }
}

class RunJournal(private val storage: RunStorage) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun save(run: RunRecord) {
        storage.write(path(run.id), json.encodeToString(RunRecord.serializer(), run))
    }

    suspend fun load(id: String): RunRecord? = storage.read(path(id))?.let {
        try { json.decodeFromString(RunRecord.serializer(), it) } catch (_: Exception) { null }
    }

    /** Newest run in this project; callers decide whether it is interrupted or history. */
    suspend fun latest(): RunRecord? = storage.directories(ROOT).mapNotNull { id ->
        load(id)
    }.maxByOrNull { it.updatedAt }

    /** Recover a live run from a previous process without pretending it finished. */
    suspend fun recoverLatest(): RunRecord? {
        val latest = latest() ?: return null
        if (latest.phase in setOf(RunPhase.Done, RunPhase.Failed, RunPhase.Stopped)) return latest
        val recovered = RunMachine.recover(latest)
        save(recovered)
        return recovered
    }

    private fun path(id: String) = "$ROOT/$id/run.json"

    companion object { const val ROOT = ".rivet/runs" }
}

internal object RunContext {
    const val KIND = "run_journal"

    fun content(run: RunRecord): String? {
        if (run.phase in setOf(RunPhase.Done, RunPhase.Stopped)) return null
        // A brand-new run has nothing to remember; adding it here would change the
        // stable per-turn context event count for every ordinary turn.
        if (run.phase == RunPhase.Running && run.lastCompletedStep == 0) return null
        return buildString {
            append("Run journal (untrusted app observation, not permission).\n")
            append("Run ").append(run.title.take(160)).append(" is ")
            append(run.plainState()).append(". Last completed step: ")
            append(run.lastCompletedStep).append(".\n")
            if (run.phase != RunPhase.Running && run.phase != RunPhase.Failed) {
                append("You were interrupted; here is what already happened. Do not repeat finished steps.\n")
                run.steps.takeLast(20).forEach { step ->
                    append("- ").append(step.index).append(". ").append(step.summary.take(200)).append('\n')
                }
            }
        }
    }

    fun updates(messages: List<AgentMessage>, workspace: String, run: RunRecord?): List<AgentMessage> {
        val content = run?.let(::content) ?: return emptyList()
        val digest = InternalContext.digest(content)
        val current = messages.asReversed().firstNotNullOfOrNull { message ->
            message.validatedContext()?.takeIf {
                it.kind == KIND && it.workspaceId == workspace
            }?.digest
        }
        if (current == digest) return emptyList()
        return listOf(InternalContext(kind = KIND, workspaceId = workspace,
            content = content, digest = digest).event())
    }
}
