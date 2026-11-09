package com.kaiser.rivet.local

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogueTest {
    private fun catalogueFile(): File =
        listOf(File("src/main/assets/local-models.json"), File("app/src/main/assets/local-models.json"))
            .first { it.isFile }

    @Test
    fun bundledCatalogueHasTheVerifiedLaunchSet() {
        val entries = ModelCatalogue.parse(
            catalogueFile().readText())
        assertEquals(6, entries.size)
        assertEquals(setOf("Qwen3 0.6B", "Llama 3.2 1B Instruct", "Qwen3 1.7B",
            "Llama 3.2 3B Instruct", "Qwen3 4B Instruct", "Qwen3 8B"), entries.map { it.name }.toSet())
        assertTrue(entries.all { it.file.endsWith(".gguf") })
        assertTrue(entries.all { it.sha256?.length == 64 })
    }

    @Test
    fun validationRejectsANonGgufFileBeforeDownload() {
        val entry = ModelCatalogue.parse(
            catalogueFile().readText()).first()
        val bad = entry.copy(file = "model.safetensors")
        try {
            ModelCatalogue.validate(listOf(bad))
            throw AssertionError("expected validation failure")
        } catch (expected: CatalogueFailure) {
            assertTrue(expected.message.orEmpty().contains("GGUF"))
        }
    }

    @Test
    fun validationRejectsDuplicateEntries() {
        val entry = ModelCatalogue.parse(
            catalogueFile().readText()).first()
        try {
            ModelCatalogue.validate(listOf(entry, entry))
            throw AssertionError("expected validation failure")
        } catch (expected: CatalogueFailure) {
            assertTrue(expected.message.orEmpty().contains("Duplicate"))
        }
    }
}
