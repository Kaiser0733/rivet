package com.kaiser.rivet.provider

import com.kaiser.rivet.agent.AgentToolCall

sealed class ProviderError(message: String) : Exception(message) {
    class Unauthorized(val detail: String? = null) : ProviderError("unauthorized")
    class Forbidden(val detail: String? = null) : ProviderError("forbidden")
    class RateLimited : ProviderError("rate limited")
    class ResourceExhausted : ProviderError("resource exhausted")
    class UsageLimit : ProviderError("provider usage limit")
    class ContextOverflow : ProviderError("context overflow")
    class ModelNotFound(val model: String) : ProviderError("model not found: $model")
    class UnsupportedEndpoint : ProviderError("endpoint not supported")
    class UnsupportedConfiguration : ProviderError("unsupported generation configuration")
    class MalformedUrl(val url: String) : ProviderError("malformed url")
    class Network(val reason: String) : ProviderError("network: $reason")
    // "socket" = nothing arrived at all (transport idle timeout); "progress" = lines
    // arrived but no data payload for the progress budget. They read the same to the
    // user, so the difference has to be recorded here to be diagnosable at all.
    class Timeout(val stage: String = "idle") : ProviderError("timeout")
    class InvalidResponse(val detail: String, val userText: String? = null) : ProviderError("invalid response: $detail")
    class IncompleteGeneration(
        val reason: String,
        val partialText: String = "",
        val truncatedToolCalls: List<AgentToolCall> = emptyList(),
        val transportState: String? = null,
    ) : ProviderError("generation incomplete: $reason") {
        val isLengthCut: Boolean
            get() = reason in setOf("length", "max_tokens", "MAX_TOKENS", "max_output_tokens")
    }
    class ResponseTooLarge : ProviderError("provider response exceeded Rivet's size limit")
    class Server(val status: Int, val detail: String? = null) : ProviderError("server error: $status")
    object EmptyResponse : ProviderError("empty response")
    class ProviderMessage(val text: String, val statusCode: Int? = null) : ProviderError(text)

    // Keep provider wire messages out of normal Chat; they may contain
    // implementation details or request fragments.
    fun text(): String = when (this) {
        is Unauthorized -> "Authentication failed. Check the API key." + said(detail)
        is Forbidden -> "The provider rejected access for this key." + said(detail)
        is RateLimited -> "Rate limited by the provider. Wait a moment and try again."
        is ResourceExhausted -> "The provider's request capacity is exhausted. Wait before trying again or choose another model."
        is UsageLimit -> "The provider account has reached its usage limit. Check its billing or usage settings."
        is ContextOverflow -> "The model's context is full. Rivet could not reduce this request enough to continue."
        is ModelNotFound -> "Model \"$model\" was not found by this provider."
        is UnsupportedEndpoint -> "This endpoint is not supported by the provider."
        is UnsupportedConfiguration -> "This model's output limit is too small for manual thinking. Select a model with a larger output limit or set reasoning to Provider default."
        is MalformedUrl -> "The base URL is not a valid URL."
        is Network -> when (reason) {
            "dns" -> "Couldn't resolve the provider host. This can be temporary. Check your connection and try again. If it keeps happening, check the base URL."
            "connect" -> "Could not connect to the provider."
            "tls" -> "The TLS connection to the provider failed."
            "permission" -> "Android blocked network access for Rivet."
            else -> "The network request failed."
        }
        is Timeout -> "The provider took too long to respond."
        is InvalidResponse -> userText ?: "The provider returned a response Rivet could not parse."
        is IncompleteGeneration -> "The model stopped before finishing its response. Try again or choose another model."
        is ResponseTooLarge -> "This reply hit Rivet's 16 MB response limit. Ask the model to write or edit the file in smaller parts."
        is Server -> "Provider server error (HTTP $status)." + said(detail)
        EmptyResponse -> "The provider returned an empty response."
        is ProviderMessage -> "The model provider couldn't complete this request. Try again or check its settings."
    }
}

// One shape for quoting the provider's own words, so every banner reads the same way.
private fun said(detail: String?): String =
    if (detail.isNullOrBlank()) "" else " The provider said: \"$detail\""
