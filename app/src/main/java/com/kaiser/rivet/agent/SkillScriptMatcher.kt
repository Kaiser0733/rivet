package com.kaiser.rivet.agent

internal data class SkillScriptTarget(val name: String, val baseDir: String?)

internal data class MatchedSkillTarget(val name: String, val path: String)

/**
 * Boundary-aware matcher locating all directory skills referenced by [command] or [cwd].
 */
internal object SkillScriptMatcher {
    private fun isPathChar(value: Char): Boolean =
        value.isLetterOrDigit() || value == '.' || value == '_' || value == '-'

    private fun normalizePathForm(raw: String): String {
        var s = raw.replace('\\', '/')
        var prev: String
        do {
            prev = s
            s = s.replace("/./", "/")
            s = s.replace(Regex("""(^|[\s"'`;&|(/])\./"""), "$1")
        } while (s != prev)
        return s.replace(Regex("/+"), "/")
    }

    private fun isCdTarget(text: String, index: Int): Boolean {
        var clauseStart = index
        while (clauseStart > 0 && text[clauseStart - 1] !in ";&|(\n`") clauseStart--
        var prefix = text.substring(clauseStart, index).trimEnd()
        if (prefix.endsWith('"') || prefix.endsWith('\'')) {
            prefix = prefix.dropLast(1).trimEnd()
        }
        if (prefix.count { it == '\'' } % 2 == 1) prefix = prefix.substringAfterLast('\'')
        if (prefix.count { it == '"' } % 2 == 1) prefix = prefix.substringAfterLast('"')
        val tokens = prefix.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val verb = tokens.firstOrNull() ?: return false
        if (verb != "cd" && verb != "pushd") return false
        return tokens.drop(1).all { it.startsWith("-") }
    }

    private fun referencedPath(text: String, index: Int, length: Int): String {
        var end = index + length
        while (end < text.length && !text[end].isWhitespace() && text[end] !in "\"'`;&|)") end++
        return text.substring(index, end)
    }

    private fun matchesTokenInText(text: String, needle: String): String? {
        if (needle.isEmpty()) return null
        var from = 0
        while (true) {
            val index = text.indexOf(needle, from)
            if (index < 0) return null
            from = index + 1
            val before = text.getOrNull(index - 1)
            if (before != null && isPathChar(before)) continue
            val after = text.getOrNull(index + needle.length)
            val inside = after == '/'
            val boundary = after == null || after.isWhitespace() || after in "\"'`;&|)"
            if (inside || (boundary && isCdTarget(text, index))) {
                return referencedPath(text, index, needle.length)
            }
        }
    }

    private fun relativeSuffixForCwd(base: String, normCwd: String): String? {
        if (normCwd.isEmpty() || base.isEmpty()) return null
        if (normCwd == base || normCwd.startsWith("$base/")) return ""
        if (normCwd.endsWith("/$base") || "/$base/" in normCwd) return ""
        if (base.startsWith("$normCwd/")) return base.removePrefix("$normCwd/")
        val parts = base.split('/')
        for (i in 1 until parts.size) {
            val cwdTail = parts.subList(0, i).joinToString("/")
            if (normCwd == cwdTail || normCwd.endsWith("/$cwdTail")) {
                return parts.subList(i, parts.size).joinToString("/")
            }
        }
        return null
    }

    fun matchTargets(
        command: String,
        targets: List<SkillScriptTarget>,
        cwd: String = "",
    ): List<MatchedSkillTarget> {
        if ((command.isEmpty() && cwd.isEmpty()) || targets.isEmpty()) return emptyList()
        val text = normalizePathForm(command)
        val normCwd = normalizePathForm(cwd.trim()).trimEnd('/')
        val hits = mutableListOf<MatchedSkillTarget>()
        for (target in targets) {
            val base = target.baseDir?.let(::normalizePathForm)?.trimEnd('/') ?: continue
            if (base.isEmpty() || base.substringAfterLast('/') != target.name) continue
            val directRef = if (text.isNotEmpty()) matchesTokenInText(text, base) else null
            if (directRef != null) {
                hits += MatchedSkillTarget(target.name, directRef)
                continue
            }
            val rel = relativeSuffixForCwd(base, normCwd) ?: continue
            if (rel.isEmpty()) {
                hits += MatchedSkillTarget(target.name, normCwd)
            } else {
                val cwdRef = matchesTokenInText(text, rel)
                if (cwdRef != null) hits += MatchedSkillTarget(target.name, "$normCwd/$cwdRef")
            }
        }
        return hits
    }

    fun isGateNeeded(
        isYolo: Boolean,
        matchedNames: List<String>,
        approved: Set<String>,
    ): Boolean = !isYolo && matchedNames.any { it !in approved }
}
