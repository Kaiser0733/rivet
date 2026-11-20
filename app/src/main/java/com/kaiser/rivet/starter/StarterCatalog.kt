package com.kaiser.rivet.starter

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class Starter(
    val id: String,
    val name: String,
    val blurb: String,
    val zip: String,
    val bytes: Int,
    val brief: String,
    val zipUrl: String? = null,
    val sha256: String? = null,
    val minAppVersion: Int? = null,
)

@Serializable
private data class StarterIndex(val schema: Int, val starters: List<Starter>)

internal class StarterCatalogFailure(message: String) : IllegalArgumentException(message)

internal object StarterCatalog {
    const val DIRECTORY = "starters"
    const val ASSET_NAME = "$DIRECTORY/starters.json"
    const val MAX_ZIP_BYTES = 150_000
    const val MAX_TOTAL_BYTES = 1_000_000
    const val MAX_BRIEF_BYTES = 4096

    private val json = Json { ignoreUnknownKeys = true }
    private val idPattern = Regex("[a-z0-9]+(?:-[a-z0-9]+)*")

    fun parse(text: String): List<Starter> {
        val index = try {
            json.decodeFromString(StarterIndex.serializer(), text)
        } catch (_: IllegalArgumentException) {
            throw StarterCatalogFailure("The starter list is invalid.")
        }
        if (index.schema != 1) throw StarterCatalogFailure("Unsupported starter list version.")
        return validate(index.starters)
    }

    private fun validate(entries: List<Starter>): List<Starter> {
        if (entries.isEmpty()) throw StarterCatalogFailure("The starter list is empty.")
        val seen = HashSet<String>()
        for (entry in entries) {
            if (entry.id.length > 64 || !idPattern.matches(entry.id) || entry.zip != "${entry.id}.zip") {
                throw StarterCatalogFailure("Invalid starter path.")
            }
            if (!seen.add(entry.id)) throw StarterCatalogFailure("Duplicate starter.")
            if (entry.name.isBlank() || entry.name.length > 100 ||
                entry.blurb.isBlank() || entry.blurb.length > 320 ||
                entry.brief.isBlank() || entry.brief.toByteArray(Charsets.UTF_8).size > MAX_BRIEF_BYTES) {
                throw StarterCatalogFailure("Invalid starter description.")
            }
            if (entry.bytes !in 1..MAX_ZIP_BYTES) throw StarterCatalogFailure("Invalid starter size.")
        }
        if (entries.sumOf { it.bytes.toLong() } > MAX_TOTAL_BYTES) {
            throw StarterCatalogFailure("Starter list exceeds its size limit.")
        }
        return entries
    }
}
