package com.kaiser.rivet.provider

sealed class ProviderError(message: String) : Exception(message) {
    class Unauthorized : ProviderError("unauthorized")
    class Forbidden : ProviderError("forbidden")
    class RateLimited : ProviderError("rate limited")
    class ResourceExhausted : ProviderError("resource exhausted")
    class UsageLimit : ProviderError("provider usage limit")
    class ContextOverflow : ProviderError("context overflow")
    class ModelNotFound(val model: String) : ProviderError("model not found: $model")
    class UnsupportedEndpoint : ProviderError("endpoint not supported")
    class MalformedUrl(val url: String) : ProviderError("malformed url")
    class Network(val reason: String) : ProviderError("network: $reason")
    class Timeout : ProviderError("timeout")
    class InvalidResponse(val detail: String) : ProviderError("invalid response: $detail")
    class IncompleteGeneration(val reason: String) : ProviderError("generation incomplete: $reason")
    class ResponseTooLarge : ProviderError("provider response exceeded Rivet's size limit")
    class Server(val status: Int) : ProviderError("server error: $status")
    object EmptyResponse : ProviderError("empty response")
    class ProviderMessage(val text: String) : ProviderError(text)

    // Keep provider wire messages out of normal Chat; they may contain
    // implementation details or request fragments.
    fun text(): String = when (this) {
        is Unauthorized -> "Authentication failed. Check the API key."
        is Forbidden -> "The provider rejected access for this key."
        is RateLimited -> "Rate limited by the provider. Wait a moment and try again."
        is ResourceExhausted -> "The provider's request capacity is exhausted. Wait before trying again or choose another model."
        is UsageLimit -> "The provider account has reached its usage limit. Check its billing or usage settings."
        is ContextOverflow -> "The model's context is full. Rivet could not reduce this request enough to continue."
        is ModelNotFound -> "Model \"$model\" was not found by this provider."
        is UnsupportedEndpoint -> "This endpoint is not supported by the provider."
        is MalformedUrl -> "The base URL is not a valid URL."
        is Network -> when (reason) {
            "dns" -> "Could not resolve the host. Check the base URL and connection."
            "connect" -> "Could not connect to the provider."
            "tls" -> "The TLS connection to the provider failed."
            "permission" -> "Android blocked network access for Rivet."
            else -> "The network request failed."
        }
        is Timeout -> "The provider took too long to respond."
        is InvalidResponse -> "The provider returned a response Rivet could not parse."
        is IncompleteGeneration -> "The model stopped before finishing its response. Try again or choose another model."
        is ResponseTooLarge -> "The provider sent more data than Rivet can safely process. Try again or choose another model."
        is Server -> "Provider server error (HTTP $status)."
        EmptyResponse -> "The provider returned an empty response."
        is ProviderMessage -> "The model provider couldn't complete this request. Try again or check its settings."
    }
}
