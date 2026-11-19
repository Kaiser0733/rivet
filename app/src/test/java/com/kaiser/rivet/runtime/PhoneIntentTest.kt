package com.kaiser.rivet.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneIntentTest {
    @Test fun opensOnlyHttpsWithoutEmbeddedCredentials() {
        val allowed = "https://example.org/path?q=1"
        assertEquals(allowed, PhoneIntentSpec.https(allowed))
        for (value in listOf("javascript:alert(1)", "http://example.org", "https://a@host", "https:///missing")) {
            assertNull(PhoneIntentSpec.https(value))
        }
    }

    @Test fun packageIdsAndCuratedAliasesNeverAcceptPathsOrSpaces() {
        assertEquals("com.android.chrome", PhoneIntentSpec.packageId("Chrome"))
        assertEquals("org.example.notes", PhoneIntentSpec.packageId("org.example.notes"))
        for (value in listOf("org/example", "com.bad app", "com..broken", "unknown")) {
            assertNull(PhoneIntentSpec.packageId(value))
        }
    }

    @Test fun emailAndSmsBuildComposeOnlyRequestsWithDraftExtras() {
        val email = PhoneIntentSpec.compose("email", to = "a@example.org", subject = "Hello", body = "Draft")
        assertEquals("android.intent.action.SENDTO", email?.action)
        assertEquals("mailto", email?.scheme)
        assertEquals("a@example.org", email?.recipient)
        assertEquals("Hello", email?.extras?.get("android.intent.extra.SUBJECT"))
        assertEquals("Draft", email?.extras?.get("android.intent.extra.TEXT"))

        val sms = PhoneIntentSpec.compose("message", to = "+1234", body = "Draft")
        assertEquals("android.intent.action.SENDTO", sms?.action)
        assertEquals("smsto", sms?.scheme)
        assertEquals("+1234", sms?.recipient)
        assertEquals("Draft", sms?.extras?.get("sms_body"))
    }

    @Test fun calendarInsertsADraftWithEventTimesAndLocation() {
        val event = PhoneIntentSpec.compose("event", title = "Meet", location = "Home", start = 100, end = 200)
        assertEquals("android.intent.action.INSERT", event?.action)
        assertEquals("content://com.android.calendar/events", event?.data)
        assertEquals("Meet", event?.extras?.get("title"))
        assertEquals("Home", event?.extras?.get("eventLocation"))
        assertEquals(100L, event?.extras?.get("beginTime"))
        assertEquals(200L, event?.extras?.get("endTime"))
    }
}
