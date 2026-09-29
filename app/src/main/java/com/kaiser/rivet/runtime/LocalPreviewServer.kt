package com.kaiser.rivet.runtime

import com.kaiser.rivet.workspace.WorkspaceEntry
import com.kaiser.rivet.workspace.WorkspacePath
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URI
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Static, read-only HTTP over SAF; the listener is bound to this device's IPv4 loopback only. */
internal class LocalPreviewServer(
    root: WorkspacePath,
    entry: WorkspacePath,
    private val workspaceIsCurrent: suspend () -> Boolean,
    private val stat: suspend (WorkspacePath) -> WorkspaceEntry,
    private val copyTo: suspend (WorkspacePath, OutputStream, Long) -> Long,
) : AutoCloseable {
    private val socket = ServerSocket()
    private val active = AtomicBoolean(false)
    private val activeClients = ConcurrentHashMap.newKeySet<Socket>()
    private val rootPath = root
    private val entryPath = entry
    private lateinit var clients: ThreadPoolExecutor
    private var acceptThread: Thread? = null
    @Volatile private var invalidated: (() -> Unit)? = null

    val url: String
        get() = "http://127.0.0.1:${socket.localPort}/"

    init {
        require(!rootPath.isRoot || !entryPath.isRoot)
        require(entryPath.isWithin(rootPath))
        require((rootPath.segments + entryPath.segments).none { it == ".git" })
    }

    fun start(): String {
        check(active.compareAndSet(false, true))
        try {
            socket.reuseAddress = false
            socket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), CLIENT_QUEUE_LIMIT)
            clients = ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
                ArrayBlockingQueue(CLIENT_QUEUE_LIMIT), { task ->
                    Thread(task, "RivetPreviewClient").apply { isDaemon = true }
                }, ThreadPoolExecutor.AbortPolicy())
            acceptThread = Thread(::acceptLoop, "RivetPreviewAccept").apply {
                isDaemon = true
                start()
            }
            return url
        } catch (e: Exception) {
            active.set(false)
            try { socket.close() } catch (_: Exception) { }
            throw e
        }
    }

    fun onWorkspaceInvalidated(action: () -> Unit) {
        invalidated = action
    }

    private fun acceptLoop() {
        while (active.get()) {
            try {
                val client = socket.accept()
                if (!active.get()) {
                    client.close()
                    return
                }
                activeClients += client
                if (!active.get()) {
                    activeClients.remove(client)
                    client.close()
                    return
                }
                try { clients.execute { handle(client) } }
                catch (_: java.util.concurrent.RejectedExecutionException) {
                    activeClients.remove(client)
                    client.close()
                }
            } catch (_: SocketException) {
                if (active.get()) close()
                return
            } catch (_: Exception) {
                if (active.get()) close()
                return
            }
        }
    }

    private fun handle(client: Socket) {
        try { client.use { connection ->
            if (!active.get()) return
            connection.soTimeout = SOCKET_TIMEOUT_MS
            val input = BufferedInputStream(connection.getInputStream())
            val output = BufferedOutputStream(connection.getOutputStream())
            val request = try { readRequest(input) }
                catch (_: Exception) { respond(output, 400, "Bad Request", "Invalid request.\n"); return }
            if (request.method != "GET" && request.method != "HEAD") {
                respond(output, 405, "Method Not Allowed", "Only GET and HEAD are supported.\n",
                    extraHeaders = "Allow: GET, HEAD\r\n")
                return
            }
            val requested = try { resolve(request.target) }
                catch (_: Exception) { respond(output, 400, "Bad Request", "Invalid project path.\n"); return }
            val selected = try {
                runBlocking(Dispatchers.IO) { withTimeout(SAF_REQUEST_TIMEOUT_MS) { workspaceIsCurrent() } }
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { false }
            if (!selected) {
                respond(output, 410, "Gone", "This project is no longer selected.\n")
                invalidated?.invoke()
                return
            }
            val file = try {
                runBlocking(Dispatchers.IO) { withTimeout(SAF_REQUEST_TIMEOUT_MS) { stat(requested) } }
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { null }
            if (file == null || file.directory) {
                respond(output, 404, "Not Found", "Project file not found.\n")
                return
            }
            if (file.size != null && file.size > MAX_FILE_BYTES) {
                respond(output, 413, "Content Too Large", "This file is too large to preview.\n")
                return
            }
            val contentType = contentType(file.path.name)
            val length = file.size
            output.write("HTTP/1.1 200 OK\r\n".toByteArray(Charsets.US_ASCII))
            output.write("Content-Type: $contentType\r\n".toByteArray(Charsets.US_ASCII))
            output.write("Cache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n"
                .toByteArray(Charsets.US_ASCII))
            if (length != null) output.write("Content-Length: $length\r\n".toByteArray(Charsets.US_ASCII))
            else if (request.method == "GET") output.write("Transfer-Encoding: chunked\r\n".toByteArray(Charsets.US_ASCII))
            output.write("\r\n".toByteArray(Charsets.US_ASCII))
            output.flush()
            if (request.method == "HEAD") return
            try {
                runBlocking(Dispatchers.IO) {
                    withTimeout(SAF_REQUEST_TIMEOUT_MS) {
                        if (length == null) {
                            val chunked = ChunkedOutputStream(output)
                            copyTo(requested, chunked, MAX_FILE_BYTES)
                            chunked.finishChunked()
                        } else copyTo(requested, output, MAX_FILE_BYTES)
                    }
                }
                output.flush()
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) {
                // Headers are already sent; closing the incomplete response makes the read failure truthful.
            }
        } } catch (_: CancellationException) {
            // SAF request timeouts cancel this detached worker's local coroutine;
            // the socket has already been closed by the surrounding use block.
        } finally { activeClients.remove(client) }
    }

    private fun resolve(target: String): WorkspacePath {
        if (!target.startsWith('/') || target.startsWith("//") || '#' in target) throw IllegalArgumentException()
        val uri = URI(target)
        val decoded = uri.path ?: throw IllegalArgumentException()
        if (!decoded.startsWith('/') || '\u0000' in decoded) throw IllegalArgumentException()
        if (decoded == "/") return entryPath
        val relative = decoded.removePrefix("/")
        val segments = relative.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." || it == ".git" }) {
            throw IllegalArgumentException()
        }
        return WorkspacePath.parse(if (rootPath.isRoot) relative else "${rootPath.value}/$relative")
    }

    private fun readRequest(input: BufferedInputStream): Request {
        var headerBytes = 0
        fun line(): String {
            val bytes = ByteArrayOutputStream()
            var previous = -1
            while (true) {
                val next = input.read()
                if (next < 0) throw IllegalArgumentException()
                headerBytes++
                if (headerBytes > MAX_HEADER_BYTES) throw IllegalArgumentException()
                if (next == '\n'.code) break
                if (previous == '\r'.code) bytes.write(previous)
                if (next != '\r'.code) bytes.write(next)
                previous = next
                if (bytes.size() > MAX_REQUEST_LINE_BYTES) throw IllegalArgumentException()
            }
            return bytes.toByteArray().toString(Charsets.US_ASCII)
        }
        val parts = line().split(' ')
        if (parts.size != 3 || parts[0].isEmpty() || parts[1].isEmpty() ||
            parts[2] !in setOf("HTTP/1.0", "HTTP/1.1")) throw IllegalArgumentException()
        var contentLength = 0L
        var hasTransferEncoding = false
        while (true) {
            val header = line()
            if (header.isEmpty()) break
            val colon = header.indexOf(':')
            if (colon <= 0) throw IllegalArgumentException()
            when (header.substring(0, colon).trim().lowercase()) {
                "content-length" -> contentLength = header.substring(colon + 1).trim().toLongOrNull()
                    ?: throw IllegalArgumentException()
                "transfer-encoding" -> hasTransferEncoding = true
            }
        }
        if (contentLength != 0L || hasTransferEncoding) throw IllegalArgumentException()
        return Request(parts[0], parts[1])
    }

    private fun respond(output: OutputStream, code: Int, reason: String, body: String,
                        extraHeaders: String = "") {
        val bytes = body.toByteArray(Charsets.UTF_8)
        output.write("HTTP/1.1 $code $reason\r\nContent-Type: text/plain; charset=utf-8\r\n".toByteArray())
        output.write("Cache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n".toByteArray())
        output.write(extraHeaders.toByteArray(Charsets.US_ASCII))
        output.write("Content-Length: ${bytes.size}\r\n\r\n".toByteArray(Charsets.US_ASCII))
        output.write(bytes)
        output.flush()
    }

    override fun close() {
        if (!active.compareAndSet(true, false)) return
        try { socket.close() } catch (_: Exception) { }
        activeClients.toList().forEach { client -> try { client.close() } catch (_: Exception) { } }
        if (::clients.isInitialized) clients.shutdownNow()
        acceptThread?.interrupt()
    }

    private data class Request(val method: String, val target: String)

    private class ChunkedOutputStream(private val output: OutputStream) : OutputStream() {
        override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            if (length == 0) return
            output.write("${length.toString(16)}\r\n".toByteArray(Charsets.US_ASCII))
            output.write(bytes, offset, length)
            output.write("\r\n".toByteArray(Charsets.US_ASCII))
        }
        override fun flush() = output.flush()
        fun finishChunked() { output.write("0\r\n\r\n".toByteArray(Charsets.US_ASCII)) }
    }

    companion object {
        const val MAX_FILE_BYTES = 64L * 1024 * 1024
        private const val SOCKET_TIMEOUT_MS = 5_000
        private const val SAF_REQUEST_TIMEOUT_MS = 20_000L
        private const val MAX_HEADER_BYTES = 16 * 1024
        private const val MAX_REQUEST_LINE_BYTES = 4 * 1024
        private const val CLIENT_QUEUE_LIMIT = 2

        fun contentType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
            "html", "htm" -> "text/html; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "js", "mjs" -> "application/javascript; charset=utf-8"
            "json" -> "application/json; charset=utf-8"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "ico" -> "image/x-icon"
            "txt" -> "text/plain; charset=utf-8"
            "wasm" -> "application/wasm"
            else -> "application/octet-stream"
        }
    }
}
