package com.kaiser.rivet.agent

/**
 * Client-side slash command checks. Only the first whitespace-delimited token is a
 * command candidate; slashes later in a message are ordinary text.
 */
internal object SlashCommands {
    /** The unknown leading command name, or null when the text is not a command or is known. */
    fun unknownLeading(input: String, userSkills: List<Skill>): String? {
        val trimmed = input.trimStart()
        if (!trimmed.startsWith("/")) return null
        val token = trimmed.removePrefix("/").takeWhile { !it.isWhitespace() }
        if (token.isEmpty()) return null
        if (token == "goal") return null
        if (userSkills.any { it.userInvocable && it.name == token }) return null
        return token
    }
}
