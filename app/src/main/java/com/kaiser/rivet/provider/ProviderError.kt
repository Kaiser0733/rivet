package com.kaiser.rivet.provider

sealed class ProviderError(message: String) : Exception(message) {
    class Unauthorized : ProviderError("unauthorized")
    class Forbidden : ProviderError("forbidden")
    class RateLimited : ProviderError("rate limited")
    class ModelNotFound(val model: String) : ProviderError("model not found: $model")
    class UnsupportedEndpoint : ProviderError("endpoint not supported")
    class MalformedUrl(val url: String) : ProviderError("malformed url")
    class Network(val reason: String) : ProviderError("network: $reason")
    class Timeout : ProviderError("timeout")
    class InvalidResponse(val detail: String) : ProviderError("invalid response: $detail")
    class Server(val status: Int) : ProviderError("server error: $status")
    object Cancelled : ProviderError("cancelled")
    object EmptyResponse : ProviderError("empty response")
    class ProviderMessage(val text: String) : ProviderError(text)

    // Plain-text copy for UI surfaces; provider-reported messages pass
    // through verbatim, everything else is Rivet's own short wording.
    fun text(): String = when (this) {
        is Unauthorized -> "Authentication failed. Check the API key."
        is Forbidden -> "The provider rejected access for this key."
        is RateLimited -> "Rate limited by the provider. Wait a moment and try again."
        is ModelNotFound -> "Model \"$model\" was not found by this provider."
        is UnsupportedEndpoint -> "This endpoint is not supported by the provider."
        is MalformedUrl -> "The base URL is not a valid URL."
        is Network -> when (reason) {
            "dns" -> "Could not resolve the host. Check the base URL and connection."
            "connect" -> "Could not connect to the provider."
            "tls" -> "The TLS connection to the provider failed."
            else -> "The network request failed."
        }
        is Timeout -> "The provider took too long to respond."
        is InvalidResponse -> "The provider returned a response Rivet could not parse."
        is Server -> "Provider server error (HTTP $status)."
        Cancelled -> "Cancelled."
        EmptyResponse -> "The provider returned an empty response."
        is ProviderMessage -> text
    }
}
