package com.kaiser.rivet.browser

/** Password, OTP and payment fields are a bright line: never captured, never typed. */
data class BrowserFormField(
    val name: String = "",
    val type: String = "text",
    val autocomplete: String = "",
    val placeholder: String = "",
)

object BrowserFormSafety {
    private val sensitiveTypes = setOf("password", "hidden", "file")
    private val sensitiveAutocomplete = listOf(
        "current-password", "new-password", "one-time-code",
        "cc-number", "cc-csc", "cc-exp", "cc-name", "cc-type",
    )
    private val sensitiveName = Regex(
        "(?i)(pass(word)?|otp|one.?time|card.?number|cvv|cvc|security.?code|cc.?num|ssn)",
    )

    fun isSensitive(field: BrowserFormField): Boolean {
        val type = field.type.lowercase()
        val autocomplete = field.autocomplete.lowercase()
        if (type in sensitiveTypes) return true
        if (sensitiveAutocomplete.any { autocomplete.contains(it) }) return true
        return sensitiveName.containsMatchIn(field.name) ||
            sensitiveName.containsMatchIn(field.placeholder)
    }

    fun refusal(field: BrowserFormField): String? =
        if (isSensitive(field)) {
            "Rivet will not type into a password, one-time-code, or payment field. Use the page in your browser or take over and type it yourself."
        } else null

    fun capturable(fields: List<BrowserFormField>): List<BrowserFormField> =
        fields.filterNot(::isSensitive)
}
