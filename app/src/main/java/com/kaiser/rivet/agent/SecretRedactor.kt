package com.kaiser.rivet.agent

/** Masks registered credential values in live model-visible text, before persistence. */
internal object SecretRedactor {
    const val MASK = "[redacted credential]"

    fun redact(text: String, values: Collection<String>): String {
        if (text.isEmpty()) return text
        var result = AgentContext.redact(text)
        values.asSequence()
            .map { it.trim() }
            .filter { it.length >= 8 }
            .distinct()
            .sortedByDescending { it.length }
            .forEach { result = result.replace(it, MASK) }
        return result
    }

    fun redact(result: AgentToolResult, values: Collection<String>): AgentToolResult {
        if (values.isEmpty()) return result
        return result.copy(
            content = redact(result.content, values),
            summary = redact(result.summary, values),
        )
    }

    fun redact(message: AgentMessage, values: Collection<String>): AgentMessage {
        if (values.isEmpty()) return message
        return message.copy(
            text = redact(message.text, values),
            toolResults = message.toolResults.map { result ->
                result.copy(
                    content = redact(result.content, values),
                    summary = redact(result.summary, values),
                )
            },
        )
    }
}
