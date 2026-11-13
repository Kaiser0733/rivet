package com.kaiser.rivet.ui.provider

import com.kaiser.rivet.agent.SkillInstallFailure
import java.io.FileNotFoundException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillInstallStepPolicyTest {
    @Test fun failureMessageHandlesSecurityAndIoExceptionsWithoutCrashing() {
        assertEquals("custom failure", SkillInstallStepPolicy.failureMessage(SkillInstallFailure("custom failure")))
        assertTrue(SkillInstallStepPolicy.failureMessage(SecurityException("revoked")).contains("permission"))
        assertTrue(SkillInstallStepPolicy.failureMessage(FileNotFoundException("gone")).contains("opened"))
        assertTrue(SkillInstallStepPolicy.failureMessage(IllegalStateException("boom")).isNotBlank())
    }

    @Test fun shouldRunInitialZipDoesNotRerunHandledOrTerminalSteps() {
        val uri = "content://example/skill.zip"
        assertTrue(SkillInstallStepPolicy.shouldRunInitialZip(uri, handledZipUri = null, savedKind = null))
        assertFalse(SkillInstallStepPolicy.shouldRunInitialZip(uri, handledZipUri = uri, savedKind = null))
        assertFalse(SkillInstallStepPolicy.shouldRunInitialZip(uri, handledZipUri = null, savedKind = SkillInstallStepPolicy.KIND_INSTALLED))
        assertFalse(SkillInstallStepPolicy.shouldRunInitialZip(uri, handledZipUri = null, savedKind = SkillInstallStepPolicy.KIND_FAILED))
        assertFalse(SkillInstallStepPolicy.shouldRunInitialZip(null, handledZipUri = null, savedKind = null))
    }
}
