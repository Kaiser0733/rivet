package com.kaiser.rivet.local

import android.content.Context
import com.kaiser.rivet.provider.http
import com.kaiser.rivet.provider.quick
import com.kaiser.rivet.provider.readBoundedBody
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

@Serializable
enum class LocalToolCalling {
    @SerialName("verified") Verified,
    @SerialName("prompted") Prompted,
    @SerialName("none") None,
}

@Serializable
enum class LocalCategory {
    @SerialName("Coding") Coding,
    @SerialName("General") General,
}

/** The catalogue contract shared by the bundled asset and the optional remote copy. */
@Serializable
data class LocalModelEntry(
    val name: String,
    val repo: String,
    val file: String,
    val size: Long,
    val params: String,
    /** Minimum phone RAM in MiB, matching the safe ceiling table. */
    val ramFloor: Int,
    val contextMax: Int,
    val categories: List<LocalCategory>,
    val toolCalling: LocalToolCalling,
    val blurb: String,
    /** Verified Hugging Face LFS hash when the repo publishes one. */
    val sha256: String? = null,
) {
    val id: String get() = "$repo/$file"
    val fileName: String get() = file.substringAfterLast('/')
    val paramsB: Double get() = params.removeSuffix("B").toDoubleOrNull() ?: 0.0
    val downloadUrl: String get() = "https://huggingface.co/$repo/resolve/main/$file?download=true"
}

@Serializable
private data class CatalogueDocument(val schema: Int = 1, val models: List<LocalModelEntry> = emptyList())

class CatalogueFailure(message: String) : Exception(message)

/**
 * Reads the verified launch catalogue. The remote URL is deliberately optional:
 * a stale or unavailable URL must never leave a first-run user without models.
 */
object ModelCatalogue {
    const val ASSET_NAME = "local-models.json"
    const val REMOTE_URL = "https://raw.githubusercontent.com/Kaiser0733/rivet/main/app/src/main/assets/local-models.json"

    private val json = Json { ignoreUnknownKeys = true }
    private val repoPattern = Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
    private val shaPattern = Regex("^[0-9a-fA-F]{64}$")

    fun parse(text: String): List<LocalModelEntry> {
        val document = try {
            json.decodeFromString(CatalogueDocument.serializer(), text)
        } catch (e: Exception) {
            throw CatalogueFailure("The model catalogue is not valid JSON.")
        }
        return validate(document.models)
    }

    fun validate(entries: List<LocalModelEntry>): List<LocalModelEntry> {
        if (entries.isEmpty()) throw CatalogueFailure("The model catalogue is empty.")
        val seen = HashSet<String>()
        return entries.map { entry ->
            if (!repoPattern.matches(entry.repo)) throw CatalogueFailure("Invalid model repo: ${entry.repo}")
            if (entry.file.isBlank() || entry.file.startsWith('/') || ".." in entry.file.split('/') ||
                !entry.file.lowercase(Locale.ROOT).endsWith(".gguf")) {
                throw CatalogueFailure("Invalid GGUF file: ${entry.file}")
            }
            if (entry.size <= 0L || entry.size > 20_000_000_000L) {
                throw CatalogueFailure("Invalid model size for ${entry.name}.")
            }
            if (!Regex("^[0-9]+(?:\\.[0-9]+)?B$").matches(entry.params)) {
                throw CatalogueFailure("Invalid parameter count for ${entry.name}.")
            }
            if (entry.ramFloor < 1024 || entry.ramFloor > 131_072) {
                throw CatalogueFailure("Invalid RAM floor for ${entry.name}.")
            }
            if (entry.contextMax !in 1024..262_144) {
                throw CatalogueFailure("Invalid context window for ${entry.name}.")
            }
            if (entry.categories.isEmpty() ||
                entry.categories.distinct().size != entry.categories.size) {
                throw CatalogueFailure("Invalid categories for ${entry.name}.")
            }
            if (entry.blurb.isBlank()) throw CatalogueFailure("Missing blurb for ${entry.name}.")
            entry.sha256?.let {
                if (!shaPattern.matches(it)) throw CatalogueFailure("Invalid checksum for ${entry.name}.")
            }
            if (!seen.add(entry.id)) throw CatalogueFailure("Duplicate model: ${entry.id}")
            entry
        }
    }

    suspend fun load(context: Context, client: OkHttpClient = http): List<LocalModelEntry> =
        withContext(Dispatchers.IO) {
            val remote = runCatching { loadRemote(client) }.getOrNull()
            if (remote != null) remote else loadBundled(context)
        }

    fun loadBundled(context: Context): List<LocalModelEntry> {
        val text = try {
            context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            throw CatalogueFailure("The bundled model catalogue is missing.")
        }
        return parse(text)
    }

    private fun loadRemote(client: OkHttpClient): List<LocalModelEntry> {
        val request = Request.Builder().url(REMOTE_URL).get().build()
        // The bundled asset is enough to show the funnel. A dead or slow network
        // must not hold the first screen for the provider client's full read timeout.
        client.quick().newBuilder().callTimeout(2, TimeUnit.SECONDS).build()
            .newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw CatalogueFailure("Catalogue request failed.")
                val text = response.readBoundedBody(2 * 1024 * 1024)
                    ?: throw CatalogueFailure("Catalogue response was empty.")
                return parse(text)
            }
    }
}
