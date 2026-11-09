package com.kaiser.rivet.local

import android.app.Application
import android.net.Uri
import com.kaiser.rivet.provider.ProviderConfig
import com.kaiser.rivet.provider.ProviderType
import com.kaiser.rivet.storage.ProviderStore
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class LocalFailure(message: String) : Exception(message)

@Serializable
data class InstalledLocalModel(
    val entry: LocalModelEntry,
    val byo: Boolean = false,
    val checksumVerified: Boolean = false,
    val installedAt: Long = System.currentTimeMillis(),
)

@Serializable
private data class LocalStateDocument(
    val schema: Int = 1,
    val installed: List<InstalledLocalModel> = emptyList(),
)

/** Owns the GGUF files, installed-model metadata, and the one native model slot. */
class LocalModelManager(val app: Application) {
    private val root = File(app.filesDir, "local-models").apply { mkdirs() }
    private val stateFile = File(root, "installed.json")
    private val providers = ProviderStore(app)
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val stateLock = Any()
    private val nativeLock = Any()

    private val _installed = MutableStateFlow(loadInstalled())
    val installed: StateFlow<List<InstalledLocalModel>> = _installed.asStateFlow()
    private val _loadedId = MutableStateFlow<String?>(null)
    val loadedId: StateFlow<String?> = _loadedId.asStateFlow()
    @Volatile private var active: LoadedLocal? = null

    private data class LoadedLocal(val entry: LocalModelEntry, val model: LlamaModel)

    fun entry(id: String?): LocalModelEntry? =
        _installed.value.firstOrNull { it.entry.id == id }?.entry

    fun installedFor(id: String?): InstalledLocalModel? =
        _installed.value.firstOrNull { it.entry.id == id }

    fun fileFor(entry: LocalModelEntry): File = File(root, "${hash(entry.id).take(32)}.gguf")

    fun partFor(entry: LocalModelEntry): File = File(root, "${hash(entry.id).take(32)}.gguf.part")

    fun hash(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") {
            "%02x".format(it)
        }

    fun providerId(entry: LocalModelEntry): String = "local-${hash(entry.id).take(12)}"

    fun providerConfig(entry: LocalModelEntry): ProviderConfig = ProviderConfig(
        id = providerId(entry),
        type = ProviderType.Local,
        name = "Local · ${entry.name}",
        baseUrl = ProviderType.Local.defaultBaseUrl,
        model = entry.id,
        manualContextWindowTokens = entry.contextMax,
        manualMaxOutputTokens = (entry.contextMax / 4).coerceIn(256, 1024),
    )

    /** Registers one local provider per installed GGUF; removes providers whose file disappeared. */
    suspend fun syncProviders(selectEntry: LocalModelEntry? = null) {
        val current = providers.configSnapshot()
        val models = _installed.value
        val wanted = models.map { providerConfig(it.entry) }
        for (config in wanted) providers.save(config)
        val wantedIds = wanted.map { it.id }.toSet()
        for (old in current.filter { it.type == ProviderType.Local && it.id !in wantedIds }) {
            providers.delete(old.id)
        }
        if (selectEntry != null) {
            val target = providerConfig(selectEntry)
            providers.save(target)
            providers.setActive(target.id)
        }
    }

    suspend fun activate(entry: LocalModelEntry) {
        syncProviders(entry)
    }

    suspend fun install(entry: LocalModelEntry, byo: Boolean, checksumVerified: Boolean) {
        val file = fileFor(entry)
        if (!file.isFile || file.length() != entry.size) {
            throw LocalFailure("The downloaded model file is incomplete.")
        }
        val model = InstalledLocalModel(entry, byo, checksumVerified)
        synchronized(stateLock) {
            val next = _installed.value.filterNot { it.entry.id == entry.id } + model
            _installed.value = next
            persist(next)
        }
        syncProviders()
    }

    suspend fun remove(entry: LocalModelEntry) {
        synchronized(nativeLock) {
            if (active?.entry?.id == entry.id) closeLocked()
        }
        fileFor(entry).delete()
        partFor(entry).delete()
        synchronized(stateLock) {
            val next = _installed.value.filterNot { it.entry.id == entry.id }
            _installed.value = next
            persist(next)
        }
        _loadedId.value = null
        syncProviders()
    }

    /** Loads B only after freeing A, all under one lock. */
    fun generate(
        entry: LocalModelEntry,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        grammar: String?,
        onToken: (String) -> Unit,
    ): String = synchronized(nativeLock) {
        val model = ensureLoadedLocked(entry)
        LlamaBridge.generate(model, prompt, maxTokens, temperature, topP, grammar, onToken)
    }

    fun stopGeneration() {
        // Do not take nativeLock here: the generation thread holds it while
        // nativeGenerate runs. The atomic stop flag is the only safe crossing.
        active?.let { LlamaBridge.stop(it.model) }
    }

    fun templateForPrompt(entry: LocalModelEntry, roles: List<String>, contents: List<String>): String =
        synchronized(nativeLock) {
            LlamaBridge.applyTemplate(ensureLoadedLocked(entry), roles, contents, true)
        }

    private fun ensureLoadedLocked(entry: LocalModelEntry): LlamaModel {
        val current = active
        if (current?.entry?.id == entry.id) return current.model
        if (current != null) closeLocked()
        val file = fileFor(entry)
        if (!file.isFile || file.length() != entry.size) {
            throw LocalFailure("This model's file is missing. Download it again from Models.")
        }
        if (!LlamaBridge.isAvailable()) {
            throw LocalFailure("Local inference isn't available in this build of Rivet.")
        }
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
        val loaded = LlamaBridge.load(file.absolutePath, entry.contextMax, threads)
            ?: throw LocalFailure("Rivet couldn't load this model. The file may be damaged.")
        active = LoadedLocal(entry, loaded)
        _loadedId.value = entry.id
        return loaded
    }

    private fun closeLocked() {
        active?.let { LlamaBridge.free(it.model) }
        active = null
        _loadedId.value = null
    }

    fun close() {
        synchronized(nativeLock) { closeLocked() }
    }

    fun tokenCount(entry: LocalModelEntry, text: String): Int {
        val model = synchronized(nativeLock) { ensureLoadedLocked(entry) }
        return LlamaBridge.tokenize(model, text, true).size
    }

    private fun loadInstalled(): List<InstalledLocalModel> {
        val parsed = runCatching {
            if (!stateFile.isFile) emptyList()
            else json.decodeFromString(LocalStateDocument.serializer(), stateFile.readText()).installed
        }.getOrDefault(emptyList())
        // A file deleted outside Rivet must not leave a provider pointing at nothing.
        return parsed.filter { it.entry.size > 0 && fileFor(it.entry).isFile }
    }

    private fun persist(models: List<InstalledLocalModel>) {
        runCatching {
            val temp = File(root, "installed.json.tmp")
            temp.writeText(json.encodeToString(LocalStateDocument.serializer(),
                LocalStateDocument(installed = models)))
            if (!temp.renameTo(stateFile)) {
                stateFile.writeText(temp.readText())
                temp.delete()
            }
        }
    }

    suspend fun storageBytes(): Long = withContext(Dispatchers.IO) {
        root.listFiles()?.sumOf { it.length() } ?: 0L
    }

    suspend fun export(entry: LocalModelEntry, uri: Uri) = withContext(Dispatchers.IO) {
        val file = fileFor(entry)
        if (!file.isFile) throw LocalFailure("This model's file is missing.")
        val output = app.contentResolver.openOutputStream(uri)
            ?: throw LocalFailure("Rivet couldn't open the export destination.")
        output.use { target -> file.inputStream().use { source -> source.copyTo(target) } }
    }

    suspend fun activeProvider(): ProviderConfig? {
        val id = providers.activeIdSnapshot() ?: return null
        return providers.configSnapshot().firstOrNull { it.id == id && it.type == ProviderType.Local }
    }
}
