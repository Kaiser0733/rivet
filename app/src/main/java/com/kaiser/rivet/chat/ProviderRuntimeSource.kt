package com.kaiser.rivet.chat

import android.app.Application
import com.kaiser.rivet.provider.ChatGptTokenResult
import com.kaiser.rivet.provider.ProviderConfig
import com.kaiser.rivet.provider.ProviderType
import com.kaiser.rivet.provider.ReasoningLevel
import com.kaiser.rivet.provider.resolveChatGptToken
import com.kaiser.rivet.runtime.CheckpointFailure
import com.kaiser.rivet.runtime.MirrorFailure
import com.kaiser.rivet.storage.ProviderStore
import com.kaiser.rivet.storage.SecretStore

internal fun undoFailureMessage(error: Throwable?): String = when {
    error is CheckpointFailure && error.code == "undo_desync" ->
        "Rivet restored its copy but could not write it back to the project, so the two may differ. Check the files before continuing."
    error is CheckpointFailure && error.code == "undo_conflict" ||
        error is MirrorFailure && error.code == "conflict" ->
        "The project changed after Rivet's edits, so Undo stopped before overwriting newer work."
    error is MirrorFailure && error.code in setOf("sync_required", "mirror_dirty", "previous_dirty") ->
        "Rivet found unfinished project changes and stopped Undo to avoid overwriting them."
    error is MirrorFailure && error.code in setOf("workspace_changed", "workspace_unavailable") ->
        "This project is no longer selected. Choose it again before undoing."
    else -> "Rivet couldn't finish Undo. Check the project before trying again."
}

internal fun runtimeFailureMessage(code: String?): String = when (code) {
    "process_limit" -> "Rivet already has several project operations running. Stop one in Processes, then try again."
    "foreground_service_unavailable" -> "Rivet couldn't keep the local preview running. Try again while Rivet is open."
    "preview_unavailable" -> "Rivet couldn't start a local preview for this project. Check the selected files and try again."
    "workspace_unavailable", "runtime_unavailable" ->
        "Rivet couldn't prepare command access for this project. Choose the project again and retry."
    "workspace_changed" ->
        "The project changed while Rivet was working, so it stopped before running the command."
    "sync_required", "mirror_dirty" ->
        "Rivet found pending project changes it couldn't safely reconcile. It kept the pending copy and did not overwrite newer project files."
    "sync_conflict", "conflict" ->
        "The project changed outside Rivet. Rivet kept its pending changes and stopped before overwriting newer work."
    "sync_failed" ->
        "Rivet couldn't finish saving the command's project changes. Some files may already be saved; Rivet kept the pending changes."
    "sync_interrupted", "interrupted" ->
        "The command ran, but Rivet couldn't confirm its project changes. Check the project before retrying."
    "checkpoint_unavailable" ->
        "Rivet couldn't prepare a safe Undo, so it stopped before running the command. Try again."
    "unsafe_entry" ->
        "Rivet found a project item it couldn't safely access, so it stopped. Remove or rename that item before trying again."
    else -> "Rivet couldn't start the project command. Check the project and try again."
}

// Kaiser's ruling on the device, 2026-11-02: the old refusal was incomprehensible to a
// non-technical user, quoted a number nothing compared against, and told someone who had worked
// for hours to start over. No numbers, no "budget", no "token", no "context window", and no
// "start a new conversation" anywhere in this family. What is left is what happened, that nothing
// was lost, and one thing to try.
internal fun contextUnavailableMessage(): String =
    "This chat is too long for the model to read all at once, even after Rivet shortened it. " +
        "Your messages are safe. Try again, and if it keeps stopping here, remove the longest message."

internal fun contextTooSmallMessage(): String =
    "The newest message is too big for this model to read. Your earlier messages are safe. " +
        "Shorten it and send it again."

internal sealed interface ProviderRuntimeResult {
    data class Ready(val config: ProviderConfig, val apiKey: String) : ProviderRuntimeResult
    data class Failure(val message: String) : ProviderRuntimeResult
}

internal fun interface ProviderRuntimeSource {
    suspend fun load(): ProviderRuntimeResult
    suspend fun loadFor(configId: String): ProviderRuntimeResult =
        ProviderRuntimeResult.Failure("The selected provider no longer exists.")
    suspend fun configs(): List<ProviderConfig> =
        (load() as? ProviderRuntimeResult.Ready)?.let { listOf(it.config) }.orEmpty()
    suspend fun updateReasoning(providerId: String, level: ReasoningLevel) {}
}

internal class StoredProviderRuntimeSource(
    private val providerStore: ProviderStore,
    private val secrets: SecretStore,
) : ProviderRuntimeSource {
    constructor(app: Application) : this(ProviderStore(app), SecretStore(app))

    override suspend fun load(): ProviderRuntimeResult {
        val activeId = providerStore.activeIdSnapshot()
            ?: return ProviderRuntimeResult.Failure("Choose a model first. Add one in Settings.")
        return loadFor(activeId)
    }

    override suspend fun loadFor(configId: String): ProviderRuntimeResult {
        val config = providerStore.configSnapshot().firstOrNull { it.id == configId }
            ?: return ProviderRuntimeResult.Failure("The selected provider no longer exists.")
        return resolveConfig(config)
    }

    override suspend fun configs(): List<ProviderConfig> = providerStore.configSnapshot()

    private fun resolveConfig(config: ProviderConfig): ProviderRuntimeResult {
        if (config.type == ProviderType.Local) {
            // Local GGUF models have no API key and never touch a remote endpoint.
            return ProviderRuntimeResult.Ready(config, "")
        }
        if (config.type == ProviderType.ChatGptPlan) {
            // OAuth access token (refreshed if expired); transient refresh errors stay retryable.
            return when (val token = resolveChatGptToken(secrets, config)) {
                is ChatGptTokenResult.Ready ->
                    ProviderRuntimeResult.Ready(config, token.accessToken)
                ChatGptTokenResult.NetworkFailed ->
                    ProviderRuntimeResult.Failure("Could not reach ChatGPT to refresh your session. Check your connection and try again.")
                ChatGptTokenResult.ReauthRequired ->
                    ProviderRuntimeResult.Failure("ChatGPT sign-in expired. Sign in again in Settings.")
            }
        }
        val apiKey = secrets.apiKey(config.id)
            ?: return ProviderRuntimeResult.Failure("No API key stored for \"${config.name}\".")
        return ProviderRuntimeResult.Ready(config, apiKey)
    }

    override suspend fun updateReasoning(providerId: String, level: ReasoningLevel) {
        val current = providerStore.configSnapshot().firstOrNull { it.id == providerId } ?: return
        if (current.reasoning != level) {
            providerStore.save(current.copy(reasoning = level))
        }
    }
}
