package com.kaiser.rivet.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillBundleFileTest {
    @Test fun aZipIsAcceptedWhateverTheProviderCallsIt() {
        assertTrue(isSkillBundle("word-count.zip", "application/zip"))
        assertTrue(isSkillBundle("word-count.ZIP", "application/octet-stream"))
        assertTrue(isSkillBundle("bundle", "application/x-zip-compressed"))
        assertNull(skillBundleRefusal("word-count.zip", "application/zip"))
        assertTrue(SKILL_BUNDLE_MIME_TYPES.contains("application/zip"))
    }

    @Test fun aStillImagePickedByMistakeIsRefusedInPlainWords() {
        val refusal = skillBundleRefusal("photo.jpg", "image/jpeg")
        assertFalse(isSkillBundle("photo.jpg", "image/jpeg"))
        assertTrue(requireNotNull(refusal).contains("zip"))
    }

    @Test fun aSingleTopFolderBundleIsStillOneSkill() {
        // The staged word-count bundle: SKILL.md inside one top folder.
        assertTrue(zipSkillRoots(listOf("word-count/SKILL.md", "word-count/readme.md")) == listOf("word-count/"))
    }
}
