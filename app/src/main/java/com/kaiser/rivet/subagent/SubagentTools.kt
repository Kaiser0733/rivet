package com.kaiser.rivet.subagent

import com.kaiser.rivet.agent.AgentApprovalRequest
import com.kaiser.rivet.agent.AgentToolCall
import com.kaiser.rivet.agent.AgentToolDefinition
import com.kaiser.rivet.agent.AgentToolEffect
import com.kaiser.rivet.agent.AgentToolError
import com.kaiser.rivet.agent.AgentToolResult
import com.kaiser.rivet.agent.BasicAutonomyRisk
import com.kaiser.rivet.agent.PreparedAgentTool
import com.kaiser.rivet.agent.Skill
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

internal sealed interface SpawnValidation {
    data class Valid(
        val brief: WorkerBrief,
        val entry: ResolvedPoolEntry,
        val skill: Skill?,
    ) : SpawnValidation

    data class Rejected(
        val code: String,
        val detail: String,
    ) : SpawnValidation
}

internal data class SpawnExecutionResult(
    val json: String,
    val error: Boolean = false,
)

/**
 * Coordinator-only delegation tools (`spawn_subagent` and `await_subagents`).
 * Never included in a worker's tool definitions or router, enforcing depth 1 structurally.
 */
internal class SubagentTools(
    private val supervisor: SubagentSupervisor,
    private val workerStore: WorkerSessionStore,
    private val sessionId: () -> String?,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun prepare(call: AgentToolCall): PreparedAgentTool? = when (call.name) {
        SPAWN_TOOL -> prepareSpawn(call)
        AWAIT_TOOL -> prepareAwait(call)
        else -> null
    }

    private suspend fun prepareSpawn(call: AgentToolCall): PreparedAgentTool {
        return when (val validated = supervisor.validateSpawn(call.arguments)) {
            is SpawnValidation.Rejected -> PreparedAgentTool(
                call = call,
                approval = null,
                effect = AgentToolEffect.ReadOnly,
            ) {
                AgentToolResult(
                    callId = call.id,
                    name = call.name,
                    content = buildJsonObject {
                        put("error", validated.code)
                        put("retryable", false)
                        put("required_action", validated.detail)
                    }.toString(),
                    error = true,
                    summary = "Failed  $SPAWN_TOOL",
                )
            }
            is SpawnValidation.Valid -> {
                val currentSession = sessionId()
                val alreadyGranted = workerStore.hasSpawnGrant(currentSession)
                val approval = if (alreadyGranted) null else AgentApprovalRequest(
                    call = call,
                    title = SPAWN_APPROVAL_TITLE,
                    detail = SPAWN_APPROVAL_DETAIL,
                )
                PreparedAgentTool(
                    call = call,
                    approval = approval,
                    effect = AgentToolEffect.Delegation,
                    basicAutonomyRisk = BasicAutonomyRisk.Elevated,
                ) {
                    workerStore.grantSpawn(currentSession)
                    val outcome = supervisor.executeSpawn(validated.brief, validated.entry, validated.skill)
                    AgentToolResult(
                        callId = call.id,
                        name = call.name,
                        content = outcome.json,
                        error = outcome.error,
                        summary = if (outcome.error) "Failed  $SPAWN_TOOL"
                        else "Spawned worker  ${validated.brief.job}",
                    )
                }
            }
        }
    }

    private fun prepareAwait(call: AgentToolCall): PreparedAgentTool {
        val fields = try {
            if (call.arguments.isBlank()) buildJsonObject {}
            else json.parseToJsonElement(call.arguments).jsonObject
        } catch (_: Exception) {
            return invalidAwait(call, "await_subagents arguments must be a JSON object.")
        }

        val rawIds = fields["ids"] ?: fields["worker_ids"]
        val workerIds = when {
            rawIds == null || rawIds is JsonNull -> emptyList()
            rawIds !is JsonArray -> return invalidAwait(call, "ids must be an array of worker id strings.")
            else -> {
                val parsed = mutableListOf<String>()
                for (elem in rawIds) {
                    val id = (elem as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
                        ?: return invalidAwait(call, "ids entries must be strings.")
                    if (id.isNotEmpty() && id !in parsed) parsed += id
                }
                parsed
            }
        }

        val rawTimeout = fields["timeout_s"]
        val timeoutS = when {
            rawTimeout == null || rawTimeout is JsonNull -> DEFAULT_TIMEOUT_S
            rawTimeout is JsonPrimitive && !rawTimeout.isString -> rawTimeout.intOrNull
                ?: return invalidAwait(call, "timeout_s must be an integer between 0 and $MAX_TIMEOUT_S.")
            else -> return invalidAwait(call, "timeout_s must be an integer between 0 and $MAX_TIMEOUT_S.")
        }
        if (timeoutS !in 0..MAX_TIMEOUT_S) {
            return invalidAwait(call, "timeout_s must be between 0 and $MAX_TIMEOUT_S seconds.")
        }

        return PreparedAgentTool(
            call = call,
            approval = null,
            effect = AgentToolEffect.ReadOnly,
            resultContentLimitBytes = ResultEnvelope.MAX_AWAIT_BYTES,
        ) {
            val payload = supervisor.awaitTerminal(workerIds, timeoutS.toLong() * 1_000L)
            AgentToolResult(
                callId = call.id,
                name = call.name,
                content = payload,
                error = false,
                summary = "Awaited subagents",
            )
        }
    }

    private fun invalidAwait(call: AgentToolCall, detail: String): PreparedAgentTool =
        PreparedAgentTool(call = call, approval = null, effect = AgentToolEffect.ReadOnly) {
            AgentToolResult(
                callId = call.id,
                name = call.name,
                content = buildJsonObject {
                    put("error", "invalid_arguments")
                    put("retryable", false)
                    put("required_action", detail)
                }.toString(),
                error = true,
                summary = "Failed  $AWAIT_TOOL",
            )
        }

    companion object {
        const val SPAWN_TOOL = "spawn_subagent"
        const val AWAIT_TOOL = "await_subagents"
        const val DEFAULT_TIMEOUT_S = 300
        const val MAX_TIMEOUT_S = 600

        const val SPAWN_APPROVAL_TITLE = "Allow subagents in this chat?"
        const val SPAWN_APPROVAL_DETAIL =
            "Let Rivet start subagents in this chat? They use this chat's permissions, may use any model you ticked, and spend tokens."

        private val stringSchema = buildJsonObject { put("type", "string") }
        private val stringArraySchema = buildJsonObject {
            put("type", "array")
            put("items", stringSchema)
        }
        private val timeoutSchema = buildJsonObject {
            put("type", "integer")
            put("minimum", 0)
            put("maximum", MAX_TIMEOUT_S)
        }

        private fun objectSchema(
            required: List<String>,
            vararg properties: Pair<String, JsonObject>,
        ): JsonObject = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                properties.forEach { (k, v) -> put(k, v) }
            })
            put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
            put("additionalProperties", false)
        }

        val definitions: List<AgentToolDefinition> = listOf(
            AgentToolDefinition(
                name = SPAWN_TOOL,
                description = "Start a focused background worker for parallel research, independent verification, or edits in non-overlapping file paths. Returns immediately with worker_id and state ('running' or 'waiting'). Declare exact write_scope paths ([] for read-only).",
                parameters = objectSchema(
                    required = listOf("job", "brief"),
                    "job" to stringSchema,
                    "brief" to stringSchema,
                    "write_scope" to stringArraySchema,
                    "model" to stringSchema,
                    "skill" to stringSchema,
                    "retry_of" to stringSchema,
                ),
            ),
            AgentToolDefinition(
                name = AWAIT_TOOL,
                description = "Wait for spawned subagents to finish and return their verified result envelopes. Omit ids (or pass []) to wait for all live and queued workers in this run.",
                parameters = objectSchema(
                    required = emptyList(),
                    "ids" to stringArraySchema,
                    "timeout_s" to timeoutSchema,
                ),
            ),
        )
    }
}
