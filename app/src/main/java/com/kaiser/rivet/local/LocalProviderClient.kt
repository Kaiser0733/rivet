package com.kaiser.rivet.local

import android.app.Application
import com.kaiser.rivet.agent.AgentMessage
import com.kaiser.rivet.agent.AgentResponse
import com.kaiser.rivet.agent.AgentRole
import com.kaiser.rivet.provider.AgentRequest
import com.kaiser.rivet.provider.ModelInfo
import com.kaiser.rivet.provider.ProviderClient
import com.kaiser.rivet.provider.ProviderConfig
import com.kaiser.rivet.provider.ProviderError
import com.kaiser.rivet.provider.TestResult
import com.kaiser.rivet.provider.resolvedContextWindowTokens
import com.kaiser.rivet.provider.resolvedManualMaxOutputTokens
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The local provider speaks the same AgentRequest/AgentResponse contract as remote
 * providers. It deliberately has no API key, no spend guard, and no network client.
 */
class LocalProviderClient(
    private val config: ProviderConfig,
    private val manager: LocalModelManager,
    private val app: Application,
) : ProviderClient {
    override suspend fun listModels(): List<ModelInfo> = manager.installed.value.map {
        ModelInfo(it.entry.id, it.entry.name)
    }

    override suspend fun testConnection(): TestResult {
        val entry = manager.entry(config.model)
            ?: return TestResult(false, "This local model isn't installed. Download it from Models.")
        if (!LlamaBridge.isAvailable()) {
            return TestResult(false, "Local inference isn't available in this build of Rivet.")
        }
        val reason = LocalThermal.pauseReason(app)
        return if (reason == null) TestResult(true, "Local · ${entry.name} is ready.")
        else TestResult(false, reason)
    }

    override suspend fun streamAgent(
        request: AgentRequest,
        onReasoning: (String) -> Unit,
        onDelta: (String) -> Unit,
    ): AgentResponse {
        val entry = manager.entry(config.model)
            ?: throw ProviderError.ProviderMessage("This local model isn't installed. Download it again from Models.")
        if (request.tools.isNotEmpty()) {
            LocalToolProtocol.agentBlockReason(entry)?.let { throw ProviderError.ProviderMessage(it) }
        }
        LocalThermal.pauseReason(app)?.let { throw ProviderError.ProviderMessage(it) }

        val tools = if (entry.toolCalling == LocalToolCalling.None) emptyList()
        else LocalToolProtocol.profile(request.tools)
        val compact = config.resolvedContextWindowTokens() <= 4096
        val system = if (LocalPrompt.isWired(request.system)) request.system
        else request.system + LocalToolProtocol.systemSuffix(tools, compact = compact)
        val maxTokens = config.resolvedManualMaxOutputTokens() ?: 512

        return withContext(Dispatchers.IO) {
            try {
                val prompt = buildPrompt(entry, system, request.messages)
                var thermalPause: String? = null
                var emitted = 0
                val visible = LocalThinkingFilter()
                var text = manager.generate(
                    entry = entry,
                    prompt = prompt,
                    maxTokens = maxTokens,
                    temperature = 0.7f,
                    topP = 0.95f,
                    grammar = null,
                    onToken = { delta ->
                        visible.feed(delta).takeIf { it.isNotEmpty() }?.let(onDelta)
                        emitted++
                        // Sustained generation is where throttling actually happens. Check
                        // periodically and stop the native loop at the next token boundary.
                        if (emitted % 32 == 0 && thermalPause == null) {
                            thermalPause = LocalThermal.pauseReason(app)
                            if (thermalPause != null) manager.stopGeneration()
                        }
                    },
                )
                thermalPause?.let { throw ProviderError.ProviderMessage(it) }
                visible.finish().takeIf { it.isNotEmpty() }?.let(onDelta)
                var parsed = LocalToolProtocol.parse(text, tools.map { it.name }.toSet())
                // Prompted tool formats are the fallback path. If a model starts a tool
                // call but does not produce parseable JSON, constrain one retry with GBNF.
                if (parsed.calls.isEmpty() && tools.isNotEmpty() &&
                    entry.toolCalling == LocalToolCalling.Prompted &&
                    LocalToolProtocol.looksLikeToolAttempt(text)) {
                    val correction = "Your previous reply could not be parsed as a tool call. " +
                        "Return exactly one JSON object with \"name\" and \"arguments\" keys. " +
                        "Previous reply: " + text.take(200)
                    val retryPrompt = buildPrompt(entry, system,
                        request.messages + AgentMessage(AgentRole.User, text = correction))
                    text = manager.generate(
                        entry = entry,
                        prompt = retryPrompt,
                        maxTokens = maxTokens,
                        temperature = 0.2f,
                        topP = 0.95f,
                        grammar = LocalToolProtocol.grammar(tools),
                        onToken = {},
                    )
                    parsed = LocalToolProtocol.parse(text, tools.map { it.name }.toSet())
                }
                val answer = stripUntaggedToolMusing(LocalThinkingFilter.strip(parsed.text))
                if (answer.isBlank() && parsed.calls.isEmpty()) {
                    throw ProviderError.EmptyResponse
                }
                AgentResponse(
                    text = answer,
                    toolCalls = parsed.calls,
                    transportState = "local:${entry.toolCalling.name.lowercase()}",
                )
            } catch (e: CancellationException) {
                manager.stopGeneration()
                throw e
            } catch (e: LocalFailure) {
                throw ProviderError.ProviderMessage(e.message ?: "The local model could not run.")
            }
        }
    }

    private fun buildPrompt(entry: LocalModelEntry, system: String,
                            messages: List<AgentMessage>): String {
        val roles = mutableListOf("system")
        val contents = mutableListOf(system)
        for (message in messages) {
            when (message.role) {
                AgentRole.User -> {
                    roles += "user"
                    contents += message.text
                }
                AgentRole.Assistant -> {
                    roles += "assistant"
                    contents += assistantText(message)
                }
                AgentRole.Tool -> {
                    roles += "tool"
                    contents += message.toolResults.joinToString("\n") { result ->
                        "${result.name}: ${result.content}"
                    }
                }
                AgentRole.Context -> {
                    roles += "user"
                    contents += message.text
                }
            }
        }
        if (roles.isEmpty()) return ""
        // The manager loads the model once and reuses that same slot for generation.
        val applied = manager.templateForPrompt(entry, roles, contents)
        if (applied.isNotBlank()) return applied
        return fallbackPrompt(entry, roles, contents)
    }

    private fun assistantText(message: AgentMessage): String {
        if (message.toolCalls.isEmpty()) return message.text
        val calls = message.toolCalls.joinToString("\n") { call ->
            "<tool_call>{\"name\":${JsonPrimitive(call.name)}," +
                "\"arguments\":${call.arguments}}</tool_call>"
        }
        return listOf(message.text, calls).filter { it.isNotBlank() }.joinToString("\n")
    }

    private fun fallbackPrompt(entry: LocalModelEntry, roles: List<String>, contents: List<String>): String {
        val qwen = entry.repo.contains("qwen", ignoreCase = true) || entry.name.contains("qwen", ignoreCase = true)
        return if (qwen) {
            buildString {
                for (i in roles.indices) {
                    append("<|im_start|>").append(roles[i]).append('\n')
                    append(contents[i]).append("<|im_end|>\n")
                }
                append("<|im_start|>assistant\n")
            }
        } else {
            buildString {
                append("<|begin_of_text|>")
                for (i in roles.indices) {
                    append("<|start_header_id|>").append(roles[i]).append("<|end_header_id|>\n\n")
                    append(contents[i]).append("<|eot_id|>")
                }
                append("<|start_header_id|>assistant<|end_header_id|>\n\n")
            }
        }
    }
}
