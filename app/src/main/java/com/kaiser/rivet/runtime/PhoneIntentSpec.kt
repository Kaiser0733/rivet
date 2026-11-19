package com.kaiser.rivet.runtime

import java.net.URI
import java.util.Locale

internal data class ComposePhoneIntent(
    val label: String,
    val action: String,
    val scheme: String? = null,
    val recipient: String? = null,
    val data: String? = null,
    val extras: Map<String, Any> = emptyMap(),
)

internal object PhoneIntentSpec {
    private val packagePattern = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    private val aliases = mapOf(
        "chrome" to "com.android.chrome",
        "firefox" to "org.mozilla.firefox",
        "gmail" to "com.google.android.gm",
        "maps" to "com.google.android.apps.maps",
        "calendar" to "com.google.android.calendar",
        "messages" to "com.google.android.apps.messaging",
        "whatsapp" to "com.whatsapp",
        "youtube" to "com.google.android.youtube",
    )

    fun packageId(value: String): String? =
        aliases[value.lowercase(Locale.ROOT)] ?: value.takeIf { packagePattern.matches(it) }

    fun https(value: String): String? {
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        return value.takeIf {
            uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() && uri.userInfo == null
        }
    }

    fun compose(
        kind: String,
        to: String = "",
        subject: String? = null,
        body: String? = null,
        title: String? = null,
        location: String? = null,
        start: Long? = null,
        end: Long? = null,
        allDay: Boolean? = null,
    ): ComposePhoneIntent? {
        if (to.any { it.isISOControl() }) return null
        return when (kind) {
            "email" -> ComposePhoneIntent(
                "email", "android.intent.action.SENDTO", "mailto", to,
                extras = buildMap {
                    subject?.let { put("android.intent.extra.SUBJECT", it) }
                    body?.let { put("android.intent.extra.TEXT", it) }
                },
            )
            "message" -> ComposePhoneIntent(
                "message", "android.intent.action.SENDTO", "smsto", to,
                extras = buildMap { body?.let { put("sms_body", it) } },
            )
            "event" -> {
                if (start != null && end != null && end < start) return null
                ComposePhoneIntent(
                    "calendar event", "android.intent.action.INSERT",
                    data = "content://com.android.calendar/events",
                    extras = buildMap {
                        title?.let { put("title", it) }
                        location?.let { put("eventLocation", it) }
                        body?.let { put("description", it) }
                        start?.let { put("beginTime", it) }
                        end?.let { put("endTime", it) }
                        allDay?.let { put("allDay", it) }
                    },
                )
            }
            else -> null
        }
    }
}
