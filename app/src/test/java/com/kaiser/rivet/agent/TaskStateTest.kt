package com.kaiser.rivet.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskStateTest {
    @Test fun structuredStateKeepsWorkAndRedactsLikelyCredentials() {
        val raw = """{
            "objective":"Fix login crash",
            "userConstraints":["Do not edit src/Secrets.kt","Bearer abcdefghijklmnopqrstuvwxyz"],
            "decisions":["Keep the existing provider API"],
            "completed":["Found the null branch"],
            "current":"Update the repository",
            "pending":["Run tests"],
            "failures":["Earlier test failed"],
            "importantFiles":["src/Login.kt"],
            "verification":"Not run yet",
            "nextStep":"Patch the null branch"
        }"""
        val state = TaskState.parse(raw)!!
        assertEquals("Fix login crash", state.objective)
        assertTrue(state.userConstraints.contains("Do not edit src/Secrets.kt"))
        assertEquals(listOf("Run tests"), state.pending)
        assertEquals("Patch the null branch", state.nextStep)
        assertFalse(state.encode().contains("abcdefghijklmnopqrstuvwxyz"))
        assertTrue(state.encode().toByteArray(Charsets.UTF_8).size <= TaskState.MAX_BYTES)
    }

    @Test fun malformedOrOversizedStateCannotReplaceValidState() {
        assertNull(TaskState.parse("not JSON"))
        assertNull(TaskState.parse("{\"objective\":\"\"}"))
        assertNull(TaskState.parse("{\"objective\":\"${"x".repeat(9_000)}\"}"))
    }
}
