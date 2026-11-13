package com.kaiser.rivet.local

import java.io.File

internal object AtomicJournalWriter {
    fun write(target: File, content: String, lock: Any) {
        synchronized(lock) {
            val parent = target.parentFile ?: File(".")
            parent.mkdirs()
            val temp = File.createTempFile("${target.name}.", ".tmp", parent)
            try {
                temp.writeText(content)
                if (!temp.renameTo(target)) {
                    target.writeText(content)
                }
            } finally {
                temp.delete()
            }
        }
    }
}
