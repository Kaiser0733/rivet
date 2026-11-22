package com.kaiser.rivet.agent

internal enum class BuiltInSlash(val token: String) {
    Goal("goal"), Feedback("feedback"), Compact("compact"), Yolo("yolo"), BasicYolo("basic-yolo"),
}

/**
 * Client-side slash command checks. Only the first whitespace-delimited token is a
 * command candidate; slashes later in a message are ordinary text.
 */
internal object SlashCommands {
    private val formatting = Regex("[\\p{Cf}]")

    fun normalize(input: String): String {
        val text = input.trim().trimStart { formatting.matches(it.toString()) }
        val slashes = text.takeWhile { it == '/' || it == '／' }.length
        if (slashes == 0) return text
        val body = text.drop(slashes).trimStart()
        val token = body.takeWhile { !it.isWhitespace() }
        return "/" + token.replace(formatting, "") + body.drop(token.length)
    }

    fun builtIn(input: String): BuiltInSlash? {
        val normalized = normalize(input)
        if (!normalized.startsWith('/')) return null
        if (parseGoalCommand(normalized) != null) return BuiltInSlash.Goal
        val command = normalized.drop(1).trim()
        if (command.equals("basic yolo", ignoreCase = true)) return BuiltInSlash.BasicYolo
        return BuiltInSlash.entries.firstOrNull { it != BuiltInSlash.Goal && it.token.equals(command, ignoreCase = true) }
    }

    /** The unknown leading command name, or null when the text is not a command or is known. */
    fun unknownLeading(input: String, userSkills: List<Skill>): String? {
        val trimmed = input.trimStart()
        if (!trimmed.startsWith("/")) return null
        val token = trimmed.removePrefix("/").takeWhile { !it.isWhitespace() }
        if (token.isEmpty()) return null
        if (builtIn(input) != null) return null
        if (userSkills.any { it.userInvocable && it.name == token }) return null
        return token
    }
}
