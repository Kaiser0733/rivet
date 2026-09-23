package com.kaiser.rivet.workspace

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

data class TextSnapshot(val path: WorkspacePath, val text: String, val sha256: String, val size: Long, val modifiedTime: Long?)
data class TextEdit(val oldText: String, val newText: String)

object WorkspaceText {
    const val MAX_BYTES = 1024 * 1024

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

    fun readBounded(input: InputStream, limit: Int = MAX_BYTES, checkCancelled: () -> Unit = {}): ByteArray {
        require(limit in 0..MAX_BYTES)
        val output = ByteArrayOutputStream(minOf(limit, 8192))
        val buffer = ByteArray(8192)
        while (true) {
            checkCancelled()
            val count = input.read(buffer, 0, minOf(buffer.size, limit - output.size() + 1))
            if (count < 0) break
            if (output.size() + count > limit) throw WorkspaceFailure(WorkspaceFailure.Reason.TOO_LARGE)
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    fun isBinaryMime(mime: String, name: String = ""): Boolean {
        val type = mime.substringBefore(';').trim().lowercase(java.util.Locale.ROOT)
        if (type.endsWith("+xml")) return false
        val strongMime = type.startsWith("image/") || type.startsWith("audio/") || type.startsWith("video/") ||
            type.startsWith("font/") || type in setOf("application/pdf", "application/zip", "application/gzip")
        if (!strongMime) return false
        // Providers can call TypeScript video/mp2t. Only a matching binary
        // filename supports an early MIME rejection; other bytes are decoded.
        val extension = name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
        return name.isEmpty() || extension in setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "heic",
            "mp3", "wav", "ogg", "flac", "m4a", "mp4", "mov", "mkv", "webm", "avi",
            "ttf", "otf", "woff", "woff2", "pdf", "zip", "gz", "jar", "apk")
    }

    fun decode(bytes: ByteArray): String {
        if (bytes.size > MAX_BYTES) throw WorkspaceFailure(WorkspaceFailure.Reason.TOO_LARGE)
        if (bytes.any { (it.toInt() and 255) in 0..8 || (it.toInt() and 255) in 14..31 || it == 127.toByte() }) {
            throw WorkspaceFailure(WorkspaceFailure.Reason.BINARY)
        }
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            throw WorkspaceFailure(WorkspaceFailure.Reason.BINARY)
        }
    }

    fun encode(text: String): ByteArray {
        if (text.length > MAX_BYTES) throw WorkspaceFailure(WorkspaceFailure.Reason.TOO_LARGE)
        val bytes = try {
            val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(text))
            ByteArray(encoded.remaining()).also { encoded.get(it) }
        } catch (_: CharacterCodingException) {
            throw WorkspaceFailure(WorkspaceFailure.Reason.BINARY)
        }
        decode(bytes)
        return bytes
    }

    fun snapshot(path: WorkspacePath, bytes: ByteArray, modified: Long?) =
        TextSnapshot(path, decode(bytes), sha256(bytes), bytes.size.toLong(), modified)

    fun requireHash(bytes: ByteArray, expected: String) {
        if (sha256(bytes) != expected) throw WorkspaceFailure(WorkspaceFailure.Reason.CONFLICT)
    }

    fun patch(text: String, edits: List<TextEdit>): String {
        if (edits.isEmpty() || edits.size > 100) throw WorkspaceFailure(WorkspaceFailure.Reason.PATCH)
        var candidate = text
        for (edit in edits) {
            val index = candidate.indexOf(edit.oldText)
            if (edit.oldText.isEmpty() || index < 0 || candidate.indexOf(edit.oldText, index + 1) >= 0) {
                throw WorkspaceFailure(WorkspaceFailure.Reason.PATCH)
            }
            if (candidate.length.toLong() - edit.oldText.length + edit.newText.length > MAX_BYTES) {
                throw WorkspaceFailure(WorkspaceFailure.Reason.TOO_LARGE)
            }
            candidate = candidate.replaceRange(index, index + edit.oldText.length, edit.newText)
        }
        encode(candidate)
        return candidate
    }
}
