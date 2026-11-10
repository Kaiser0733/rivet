package com.kaiser.rivet.browser

import java.io.File

/** JSON snapshots live outside the Android saved-state bundle; thumbnails are optional files. */
class BrowserSnapshotStore(private val dir: File) {
    init { dir.mkdirs() }

    fun save(snapshot: BrowserSnapshot): Boolean = runCatching {
        val temp = File(dir, "${snapshot.id}.json.tmp")
        temp.writeText(BrowserSnapshotCodec.encode(snapshot))
        val target = File(dir, "${snapshot.id}.json")
        if (!temp.renameTo(target)) {
            target.writeText(temp.readText())
            temp.delete()
        }
        true
    }.getOrDefault(false)

    fun load(id: String): BrowserSnapshot? = runCatching {
        val file = File(dir, "$id.json")
        if (!file.isFile) null else BrowserSnapshotCodec.decode(file.readText())
    }.getOrNull()

    fun loadAll(): List<BrowserSnapshot> = dir.listFiles { file -> file.extension == "json" }
        ?.mapNotNull { file -> runCatching { BrowserSnapshotCodec.decode(file.readText()) }.getOrNull() }
        ?.sortedByDescending { it.lastUsedAt }.orEmpty()

    fun delete(id: String) {
        File(dir, "$id.json").delete()
        File(dir, "$id.webp").delete()
    }

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    fun bytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    fun enforceLimits(activeId: String?) {
        val all = loadAll()
        val capped = BrowserSnapshotCodec.capWindows(all, activeId)
        val keep = BrowserSnapshotCodec.evictForBytes(capped, { it.thumbnailFile?.let { path -> File(path).length() } ?: 0L },
            activeId).map { it.id }.toSet()
        all.filterNot { it.id in keep }.forEach { delete(it.id) }
    }
}
