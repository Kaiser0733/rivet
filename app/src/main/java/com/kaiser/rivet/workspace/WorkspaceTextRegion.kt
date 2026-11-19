package com.kaiser.rivet.workspace

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

internal data class WorkspaceTextRegion(val text: String, val sha256: String, val size: Long, val start: Long, val end: Long)

internal fun readWorkspaceTextRegion(
    input: InputStream,
    offset: Long,
    maxBytes: Int,
    checkCancelled: () -> Unit,
): WorkspaceTextRegion {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    val slice = ByteArrayOutputStream(minOf(maxBytes, 8192))
    var position = 0L
    while (true) {
        checkCancelled()
        val count = input.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
        val from = position
        val to = position + count
        if (to > offset && slice.size() < maxBytes) {
            val first = maxOf(offset, from)
            val last = minOf(offset + maxBytes, to)
            if (last > first) slice.write(buffer, (first - from).toInt(), (last - first).toInt())
        }
        position = to
    }
    val bytes = slice.toByteArray()
    if (WorkspaceText.isBinaryText(bytes)) throw WorkspaceFailure(WorkspaceFailure.Reason.BINARY)
    // A slice can start or end inside a character. Move the edges in by at
    // most three bytes rather than emitting half of one.
    var begin = 0
    while (begin < bytes.size && (bytes[begin].toInt() and 0xC0) == 0x80) begin++
    var end = bytes.size
    var trimmed = 0
    var text: String? = null
    while (end > begin && trimmed < 4) {
        text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, begin, end - begin)).toString()
        } catch (_: CharacterCodingException) {
            end--; trimmed++; null
        }
        if (text != null) break
    }
    // Nothing usable left after snapping to a character edge is an empty
    // window, not unreadable content.
    val body: String = text ?: if (end == begin) "" else throw WorkspaceFailure(WorkspaceFailure.Reason.BINARY)
    val start = minOf(offset, position) + begin
    return WorkspaceTextRegion(body, digest.digest().joinToString("") { "%02x".format(it) },
        position, start, start + (end - begin))
}
