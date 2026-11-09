package com.kaiser.rivet.local

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelRecommenderTest {
    private fun catalogueFile(): File =
        listOf(java.io.File("src/main/assets/local-models.json"),
            java.io.File("app/src/main/assets/local-models.json")).first { it.isFile }

    private val entries = ModelCatalogue.parse(
        catalogueFile().readText())
    private val gb = 1024L * 1024 * 1024

    @Test
    fun ceilingsMatchTheSafeTable() {
        assertEquals(1.0, ModelRecommender.ceilingFor(4 * 1024).maxParamsB, 0.001)
        assertEquals(4_096, ModelRecommender.ceilingFor(4 * 1024).contextMax)
        assertEquals(2.0, ModelRecommender.ceilingFor(6 * 1024).maxParamsB, 0.001)
        assertEquals(8_192, ModelRecommender.ceilingFor(6 * 1024).contextMax)
        assertEquals(4.0, ModelRecommender.ceilingFor(8 * 1024).maxParamsB, 0.001)
        assertEquals(16_384, ModelRecommender.ceilingFor(8 * 1024).contextMax)
        assertEquals(8.0, ModelRecommender.ceilingFor(12 * 1024).maxParamsB, 0.001)
        assertEquals(16_384, ModelRecommender.ceilingFor(12 * 1024).contextMax)
    }

    @Test
    fun recommendationIsTheLargestCodingModelWithinTheCeiling() {
        assertEquals("Qwen3 4B Instruct", ModelRecommender.recommend(entries, 8 * 1024, 6 * gb).primary?.name)
        assertEquals("Qwen3 8B", ModelRecommender.recommend(entries, 12 * 1024, 10 * gb).primary?.name)
        assertEquals("Qwen3 1.7B", ModelRecommender.recommend(entries, 6 * 1024, 4 * gb).primary?.name)
    }

    @Test
    fun aBusyPhoneGetsTheLighterHonestRecommendation() {
        val rec = ModelRecommender.recommend(entries, 8 * 1024, 2 * gb)
        assertTrue(rec.primary!!.paramsB < 4.0)
        assertTrue(rec.why.contains("free RAM"))
    }

    @Test
    fun tooBigIsRefusedButHeavyIsOnlyAWarning() {
        val qwen4 = entries.first { it.name == "Qwen3 4B Instruct" }
        val fit = ModelRecommender.fit(qwen4, 4 * 1024, 2 * gb)
        assertEquals(FitBadge.TooBig, fit.badge)
        assertFalse(fit.canDownload)
        val heavy = ModelRecommender.fit(qwen4, 8 * 1024, 1 * gb)
        assertEquals(FitBadge.Heavy, heavy.badge)
        assertTrue(heavy.canDownload)
    }

    @Test
    fun recommendationOptionsAreDistinctAndAtMostTwo() {
        val options = ModelRecommender.recommend(entries, 8 * 1024, 6 * gb).options
        assertEquals(2, options.size)
        assertEquals(options.size, options.distinctBy { it.id }.size)
    }
}
