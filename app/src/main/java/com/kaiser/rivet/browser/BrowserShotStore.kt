package com.kaiser.rivet.browser

import java.io.File
import java.security.MessageDigest

/** Browser screenshots live under a per-project directory, never one shared folder. */
class BrowserShotStore(private val filesDir: File, private val projectKey: String) {
    val directory: File
        get() {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(projectKey.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 0xff) }
            return File(filesDir, "browser-shots/${digest.take(24)}")
        }

    fun newShot(now: Long = System.currentTimeMillis()): File =
        File(directory.apply { mkdirs() }, "shot-$now.webp")

    /** Browser close removes this session's project shots, not another project's. */
    fun cleanSession(): Int {
        val files = directory.listFiles()?.filter { it.isFile && it.name.startsWith("shot-") }
            .orEmpty()
        files.forEach { it.delete() }
        return files.size
    }
}
