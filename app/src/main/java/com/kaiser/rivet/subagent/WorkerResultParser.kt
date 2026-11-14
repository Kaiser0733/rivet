package com.kaiser.rivet.subagent

/** The worker's own words. These are claims, never ground truth. */
data class WorkerClaim(
    val summary: String,
    val findings: List<String> = emptyList(),
    val openQuestions: List<String> = emptyList(),
    val truncated: Boolean = false,
)

/**
 * Splits a final message into the contract's three claim sections. Text without
 * headings is treated as a single summary and bounded at 1,500 characters.
 */
object WorkerResultParser {
    const val MAX_UNSECTIONED_CHARS = 1_500
    const val MAX_SECTION_CHARS = 1_500

    private val heading = Regex(
        """^\s*(?:#{1,6}\s*)?(?:\*\*)?(summary|findings|open questions)\s*:?\s*(?:\*\*)?\s*:?\s*(.*)$""",
        RegexOption.IGNORE_CASE,
    )

    fun parse(finalText: String): WorkerClaim {
        val normalized = finalText.trim()
        if (normalized.isEmpty()) return WorkerClaim("")
        val sections = linkedMapOf<String, MutableList<String>>()
        val preamble = mutableListOf<String>()
        var current: String? = null
        normalized.lines().forEach { line ->
            val match = heading.matchEntire(line.trim())
            if (match != null) {
                current = match.groupValues[1].lowercase().replace(' ', '_')
                sections.getOrPut(current!!) { mutableListOf() }
                val sameLine = match.groupValues[2].trim()
                if (sameLine.isNotEmpty()) sections.getValue(current!!).add(sameLine)
            } else {
                if (current == null) preamble += line else sections.getValue(current!!).add(line)
            }
        }
        if (sections.isEmpty()) {
            return WorkerClaim(
                summary = normalized.take(MAX_UNSECTIONED_CHARS),
                truncated = normalized.length > MAX_UNSECTIONED_CHARS,
            )
        }

        var truncated = false
        val capped: (String) -> String = { text ->
            if (text.length <= MAX_SECTION_CHARS) text
            else {
                truncated = true
                text.take(MAX_SECTION_CHARS)
            }
        }

        val summaryText = buildList {
            if (preamble.any { it.isNotBlank() }) add(preamble.joinToString("\n").trim())
            sections["summary"]?.joinToString("\n")?.trim()?.takeIf { it.isNotEmpty() }?.let(::add)
        }.joinToString("\n\n")
        val findings = sectionItems(capped, sections["findings"].orEmpty())
        val openQuestions = sectionItems(capped, sections["open_questions"].orEmpty())
        return WorkerClaim(
            summary = capped(summaryText),
            findings = findings,
            openQuestions = openQuestions,
            truncated = truncated,
        )
    }

    private fun sectionItems(cap: (String) -> String,
                             lines: List<String>): List<String> =
        lines.map { it.trim().trimStart('-', '*', '•', ' ').trim() }
            .filter { it.isNotEmpty() }
            .map(cap)
}
