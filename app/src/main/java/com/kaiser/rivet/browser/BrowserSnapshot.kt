package com.kaiser.rivet.browser

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class BrowserFidelity {
    @SerialName("full") Full,
    @SerialName("reloaded") Reloaded,
}

@Serializable
data class BrowserSnapshot(
    val id: String,
    val title: String = "",
    val historyUrls: List<String> = emptyList(),
    val historyIndex: Int = 0,
    val scrollY: Int = 0,
    val formFields: Map<String, String> = emptyMap(),
    val createdAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long = System.currentTimeMillis(),
    val fidelity: BrowserFidelity = BrowserFidelity.Reloaded,
    val thumbnailFile: String? = null,
) {
    val currentUrl: String? get() = historyUrls.getOrNull(historyIndex)
}

object BrowserSnapshotCodec {
    const val MAX_WINDOWS = 8
    const val MAX_STORE_BYTES = 20L * 1024 * 1024
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    fun encode(snapshot: BrowserSnapshot): String = json.encodeToString(BrowserSnapshot.serializer(), snapshot)

    fun decode(text: String): BrowserSnapshot? = runCatching {
        json.decodeFromString(BrowserSnapshot.serializer(), text)
    }.getOrNull()

    fun capWindows(windows: List<BrowserSnapshot>, activeId: String?): List<BrowserSnapshot> {
        if (windows.size <= MAX_WINDOWS) return windows
        val keep = windows.sortedByDescending { it.lastUsedAt }
            .filterNot { it.id == activeId }
            .take(MAX_WINDOWS - 1)
            .toMutableList()
        windows.firstOrNull { it.id == activeId }?.let { keep += it }
        return keep.sortedByDescending { it.lastUsedAt }
    }

    /** Oldest inactive snapshots go first; the active window is never evicted. */
    fun evictForBytes(windows: List<BrowserSnapshot>, bytesOf: (BrowserSnapshot) -> Long,
                      activeId: String?, maxBytes: Long = MAX_STORE_BYTES): List<BrowserSnapshot> {
        var total = windows.sumOf(bytesOf)
        val result = windows.toMutableList()
        while (total > maxBytes) {
            val victim = result.filterNot { it.id == activeId }.minByOrNull { it.lastUsedAt } ?: break
            result.remove(victim)
            total -= bytesOf(victim)
        }
        return result
    }
}
