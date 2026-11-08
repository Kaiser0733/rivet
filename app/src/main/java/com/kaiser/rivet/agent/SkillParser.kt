package com.kaiser.rivet.agent

import java.util.Locale

/** Result of reading one SKILL.md: a parsed skill (no source yet) or a one-line reason. */
sealed interface SkillParseResult {
    data class Parsed(val skill: Skill) : SkillParseResult
    data class Invalid(val reason: String) : SkillParseResult
}

private const val MAX_NAME_LENGTH = 64
private const val MAX_DESCRIPTION_LENGTH = 1024

private val SKILL_NAME = Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$")
private val TRUE_WORDS = setOf("true", "yes", "on", "1")
private val FALSE_WORDS = setOf("false", "no", "off", "0")

/**
 * Parse SKILL.md text: YAML frontmatter plus Markdown body.
 *
 * The YAML reader is a deliberately small subset so no dependency is added: plain and
 * quoted scalars, inline and block lists, and one-level maps. Anything outside the subset
 * (block scalars, nesting, broken indentation) is rejected with a reason instead of being
 * guessed at, so a malformed file can never half-load.
 */
fun parseSkill(markdown: String): SkillParseResult {
    val split = when (val value = splitFrontmatter(markdown.replace("\r\n", "\n"))) {
        is FrontmatterSplit.Missing -> return SkillParseResult.Invalid(value.reason)
        is FrontmatterSplit.Ok -> value
    }
    val fields = when (val value = parseFields(split.fields)) {
        is FieldParse.Error -> return SkillParseResult.Invalid(value.reason)
        is FieldParse.Ok -> value.fields
    }

    val (nameText, nameError) = textField(fields, "name")
    if (nameError != null) return SkillParseResult.Invalid(nameError)
    if (nameText == null) return SkillParseResult.Invalid("missing name")
    if (nameText.length > MAX_NAME_LENGTH || !SKILL_NAME.matches(nameText)) {
        return SkillParseResult.Invalid(
            "name must be lowercase letters, numbers and single hyphens, up to $MAX_NAME_LENGTH characters")
    }

    val (descriptionText, descriptionError) = textField(fields, "description")
    if (descriptionError != null) return SkillParseResult.Invalid(descriptionError)
    if (descriptionText == null) return SkillParseResult.Invalid("missing description")
    if (descriptionText.length > MAX_DESCRIPTION_LENGTH) {
        return SkillParseResult.Invalid(
            "description is ${descriptionText.length} characters; the maximum is $MAX_DESCRIPTION_LENGTH")
    }

    val (license, licenseError) = textField(fields, "license")
    if (licenseError != null) return SkillParseResult.Invalid(licenseError)
    val (compatibility, compatibilityError) = textField(fields, "compatibility")
    if (compatibilityError != null) return SkillParseResult.Invalid(compatibilityError)
    val (metadata, metadataError) = mapField(fields, "metadata")
    if (metadataError != null) return SkillParseResult.Invalid(metadataError)
    val (allowedTools, toolsError) = toolsField(fields, "allowed-tools")
    if (toolsError != null) return SkillParseResult.Invalid(toolsError)
    val (disableModelInvocation, disableError) = booleanField(fields, "disable-model-invocation", false)
    if (disableError != null) return SkillParseResult.Invalid(disableError)
    val (userInvocable, userError) = booleanField(fields, "user-invocable", true)
    if (userError != null) return SkillParseResult.Invalid(userError)

    return SkillParseResult.Parsed(
        Skill(
            name = nameText,
            description = descriptionText,
            body = split.body,
            license = license,
            compatibility = compatibility,
            metadata = metadata,
            allowedTools = allowedTools,
            disableModelInvocation = disableModelInvocation,
            userInvocable = userInvocable,
        )
    )
}

private sealed interface FrontmatterSplit {
    data class Ok(val fields: String, val body: String) : FrontmatterSplit
    data class Missing(val reason: String) : FrontmatterSplit
}

private fun splitFrontmatter(text: String): FrontmatterSplit {
    val lines = text.split('\n')
    if (lines.first().trimEnd() != "---") return FrontmatterSplit.Missing("missing YAML frontmatter")
    for (index in 1 until lines.size) {
        if (lines[index].trimEnd() != "---") continue
        return FrontmatterSplit.Ok(
            fields = lines.subList(1, index).joinToString("\n"),
            body = lines.subList(index + 1, lines.size).joinToString("\n").trim(),
        )
    }
    return FrontmatterSplit.Missing("frontmatter is not closed with ---")
}

private sealed interface YamlValue {
    data class Scalar(val value: String) : YamlValue
    data class Items(val values: List<String>) : YamlValue
    data class Mapping(val values: Map<String, String>) : YamlValue
}

private sealed interface FieldParse {
    data class Ok(val fields: Map<String, YamlValue>) : FieldParse
    data class Error(val reason: String) : FieldParse
}

private fun parseFields(text: String): FieldParse {
    val fields = LinkedHashMap<String, YamlValue>()
    var pendingKey: String? = null
    var pendingItems: MutableList<String>? = null
    var pendingMap: MutableMap<String, String>? = null

    fun flushPending() {
        val key = pendingKey ?: return
        when {
            pendingItems != null -> fields[key] = YamlValue.Items(pendingItems!!.toList())
            pendingMap != null -> fields[key] = YamlValue.Mapping(pendingMap!!.toMap())
            else -> fields[key] = YamlValue.Scalar("")
        }
        pendingKey = null
        pendingItems = null
        pendingMap = null
    }

    for ((lineNumber, raw) in text.split('\n').withIndex()) {
        if (raw.isBlank()) continue
        if (raw.trimStart().startsWith("#")) continue
        if (raw.first() == '\t') {
            return FieldParse.Error("line ${lineNumber + 1}: tabs are not allowed for indentation")
        }
        val content = raw.trimStart().trimEnd()
        val indented = raw.first() == ' '
        if (indented) {
            if (pendingKey == null) return FieldParse.Error(
                "line ${lineNumber + 1}: unexpected indentation")
            when {
                content == "-" || content.startsWith("- ") -> {
                    if (pendingMap != null) return FieldParse.Error(
                        "line ${lineNumber + 1}: list item inside a mapping")
                    val item = parseScalar(content.removePrefix("-").trim())
                        ?: return FieldParse.Error("line ${lineNumber + 1}: unsupported list item")
                    if (pendingItems == null) pendingItems = mutableListOf()
                    pendingItems!!.add(item)
                }
                ':' in content -> {
                    if (pendingItems != null) return FieldParse.Error(
                        "line ${lineNumber + 1}: mapping entry inside a list")
                    val colon = content.indexOf(':')
                    val mapKey = content.substring(0, colon).trim()
                    val mapValue = parseScalar(content.substring(colon + 1))
                        ?: return FieldParse.Error(
                            "line ${lineNumber + 1}: unsupported value for \"$mapKey\"")
                    if (mapValue.isEmpty()) return FieldParse.Error(
                        "line ${lineNumber + 1}: nested mappings are not supported")
                    if (pendingMap == null) pendingMap = LinkedHashMap()
                    pendingMap!![mapKey] = mapValue
                }
                else -> return FieldParse.Error("line ${lineNumber + 1}: unsupported YAML")
            }
            continue
        }

        flushPending()
        val colon = content.indexOf(':')
        if (colon <= 0) return FieldParse.Error("line ${lineNumber + 1}: expected key: value")
        val key = content.substring(0, colon).trim()
        if (key.isEmpty() || !key.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
            return FieldParse.Error("line ${lineNumber + 1}: unsupported key \"$key\"")
        }
        val value = content.substring(colon + 1).trim()
        when {
            value.isEmpty() -> pendingKey = key
            value.startsWith("|") || value.startsWith(">") -> return FieldParse.Error(
                "line ${lineNumber + 1}: block scalars are not supported")
            value.startsWith("[") -> fields[key] = parseInlineList(value)
                ?.let { YamlValue.Items(it) }
                ?: return FieldParse.Error("line ${lineNumber + 1}: unclosed inline list")
            value.startsWith("{") -> fields[key] = parseInlineMap(value)
                ?.let { YamlValue.Mapping(it) }
                ?: return FieldParse.Error("line ${lineNumber + 1}: unclosed inline mapping")
            else -> fields[key] = parseScalar(value)
                ?.let { YamlValue.Scalar(it) }
                ?: return FieldParse.Error("line ${lineNumber + 1}: unsupported value for \"$key\"")
        }
    }
    flushPending()
    return FieldParse.Ok(fields)
}

private fun parseScalar(raw: String): String? {
    val value = stripComment(raw).trim()
    if (value.isEmpty()) return ""
    if (value.startsWith("|") || value.startsWith(">")) return null
    if (value.startsWith("[") || value.startsWith("{")) return null
    if (value.length >= 2 && value.first() == '"' && value.last() == '"') {
        return value.substring(1, value.length - 1)
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
    }
    if (value.length >= 2 && value.first() == '\'' && value.last() == '\'') {
        return value.substring(1, value.length - 1).replace("''", "'")
    }
    if (value.first() == '"' || value.first() == '\'') return null
    return value
}

private fun parseInlineList(raw: String): List<String>? {
    if (!raw.endsWith("]")) return null
    val inner = raw.substring(1, raw.length - 1).trim()
    if (inner.isEmpty()) return emptyList()
    val values = mutableListOf<String>()
    for (part in splitTopLevel(inner, ',')) {
        values.add(parseScalar(part) ?: return null)
    }
    return values
}

private fun parseInlineMap(raw: String): Map<String, String>? {
    if (!raw.endsWith("}")) return null
    val inner = raw.substring(1, raw.length - 1).trim()
    if (inner.isEmpty()) return emptyMap()
    val values = LinkedHashMap<String, String>()
    for (part in splitTopLevel(inner, ',')) {
        val colon = part.indexOf(':')
        if (colon <= 0) return null
        val key = part.substring(0, colon).trim()
        val value = parseScalar(part.substring(colon + 1)) ?: return null
        values[key] = value
    }
    return values
}

private fun splitTopLevel(text: String, separator: Char): List<String> {
    val parts = mutableListOf<String>()
    var quote: Char? = null
    var depth = 0
    var start = 0
    for ((index, char) in text.withIndex()) {
        when {
            quote != null -> if (char == quote) quote = null
            char == '"' || char == '\'' -> quote = char
            char == '[' || char == '{' -> depth++
            char == ']' || char == '}' -> depth--
            char == separator && depth == 0 -> {
                parts.add(text.substring(start, index))
                start = index + 1
            }
        }
    }
    parts.add(text.substring(start))
    return parts
}

private fun stripComment(raw: String): String {
    var quote: Char? = null
    for ((index, char) in raw.withIndex()) {
        when {
            quote != null -> if (char == quote) quote = null
            char == '"' || char == '\'' -> quote = char
            char == '#' && (index == 0 || raw[index - 1].isWhitespace()) -> return raw.substring(0, index)
        }
    }
    return raw
}

private fun textField(fields: Map<String, YamlValue>, key: String): Pair<String?, String?> {
    return when (val field = fields[key]) {
        null -> null to null
        is YamlValue.Scalar -> field.value.trim().takeIf { it.isNotEmpty() } to null
        else -> null to "$key must be plain text"
    }
}

private fun mapField(fields: Map<String, YamlValue>, key: String): Pair<Map<String, String>, String?> {
    return when (val field = fields[key]) {
        null -> emptyMap<String, String>() to null
        is YamlValue.Mapping -> field.values to null
        else -> emptyMap<String, String>() to "$key must be a mapping of plain values"
    }
}

private fun toolsField(fields: Map<String, YamlValue>, key: String): Pair<List<String>, String?> {
    return when (val field = fields[key]) {
        null -> emptyList<String>() to null
        is YamlValue.Items -> field.values.map { it.trim() }.filter { it.isNotEmpty() } to null
        is YamlValue.Scalar -> {
            val tool = field.value.trim()
            if (tool.isEmpty()) emptyList<String>() to null else listOf(tool) to null
        }
        is YamlValue.Mapping -> emptyList<String>() to "$key must be a list of tool names"
    }
}

private fun booleanField(
    fields: Map<String, YamlValue>,
    key: String,
    default: Boolean,
): Pair<Boolean, String?> {
    return when (val field = fields[key]) {
        null -> default to null
        is YamlValue.Scalar -> {
            val word = field.value.trim().lowercase(Locale.ROOT)
            when {
                word.isEmpty() -> default to null
                word in TRUE_WORDS -> true to null
                word in FALSE_WORDS -> false to null
                else -> default to "$key must be true or false"
            }
        }
        else -> default to "$key must be true or false"
    }
}
