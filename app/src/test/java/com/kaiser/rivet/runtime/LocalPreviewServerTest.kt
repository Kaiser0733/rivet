package com.kaiser.rivet.runtime

import com.kaiser.rivet.workspace.WorkspaceEntry
import com.kaiser.rivet.workspace.WorkspacePath
import java.io.IOException
import java.net.Socket
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPreviewServerTest {
    @Test fun servesEntryAssetsAndHeadFromLoopbackWithoutDirectoryListing() {
        var selected = true
        val files = mapOf(
            "site/index.html" to "<h1>Rivet preview</h1>".toByteArray(),
            "site/style.css" to "body { color: red; }".toByteArray(),
        )
        val entries = files.mapValues { (path, bytes) ->
            WorkspaceEntry(WorkspacePath.parse(path), path, false,
                if (path.endsWith(".html")) "text/html" else "text/css", bytes.size.toLong())
        } + ("site" to WorkspaceEntry(WorkspacePath.parse("site"), "site", true,
            "vnd.android.document/directory")) + ("site/sub" to WorkspaceEntry(
            WorkspacePath.parse("site/sub"), "site/sub", true, "vnd.android.document/directory"))
        val server = LocalPreviewServer(
            WorkspacePath.parse("site"), WorkspacePath.parse("site/index.html"),
            workspaceIsCurrent = { selected },
            stat = { entries[it.value] ?: error("missing") },
            copyTo = { path, output, max ->
                val bytes = files.getValue(path.value)
                require(bytes.size.toLong() <= max)
                output.write(bytes)
                bytes.size.toLong()
            },
        )
        val url = server.start()
        try {
            assertTrue(url.startsWith("http://127.0.0.1:"))
            assertTrue(request(server, "GET", "/").contains("<h1>Rivet preview</h1>"))
            val css = request(server, "HEAD", "/style.css")
            assertTrue(css.startsWith("HTTP/1.1 200"))
            assertTrue(css.contains("Content-Type: text/css; charset=utf-8"))
            assertFalse(css.contains("color: red"))
            assertTrue(request(server, "GET", "/sub").startsWith("HTTP/1.1 404"))
            assertTrue(request(server, "GET", "/%2e%2e/secret.txt").startsWith("HTTP/1.1 400"))
            assertTrue(request(server, "POST", "/").startsWith("HTTP/1.1 405"))
            val missing = request(server, "GET", "/missing.js")
            assertTrue(missing.startsWith("HTTP/1.1 404"))
            assertFalse(missing.contains("content://"))
        } finally { server.close() }
        val afterClose = try { request(url, "GET", "/") } catch (_: IOException) { "" }
        assertFalse(afterClose.contains("<h1>Rivet preview</h1>"))
    }

    @Test fun rootRelativeAndProjectRelativeEntriesServeTheSameMultiFileSite() {
        val files = mapOf("site/index.html" to "<link href=\"style.css\"><script src=\"app.js\"></script>",
            "site/style.css" to "body { color: red; }", "site/app.js" to "window.rivet = true;")
        for (entry in listOf("index.html", "site/index.html")) {
            val paths = PreviewPaths.parse("site", entry)
            assertTrue(paths.entry.value == "site/index.html")
            val requested = mutableListOf<String>()
            val server = LocalPreviewServer(paths.root, paths.entry, workspaceIsCurrent = { true },
                stat = { path ->
                    requested += path.value
                    val content = files[path.value] ?: throw IllegalArgumentException()
                    WorkspaceEntry(path, path.value, false, "text/plain", content.toByteArray().size.toLong())
                },
                copyTo = { path, output, _ ->
                    val bytes = files.getValue(path.value).toByteArray()
                    output.write(bytes)
                    bytes.size.toLong()
                })
            server.start()
            try {
                assertTrue(request(server, "GET", "/").contains(files.getValue("site/index.html")))
                assertTrue(request(server, "GET", "/style.css").contains(files.getValue("site/style.css")))
                assertTrue(request(server, "GET", "/app.js").contains(files.getValue("site/app.js")))
                assertTrue(requested == listOf("site/index.html", "site/style.css", "site/app.js"))
                for (target in listOf("/../secret", "/%2e%2e/secret", "/sub/%2e%2e/secret", "/.git/config",
                    "/%2egit/config", "/%2f%2e%2e/secret")) {
                    assertTrue(target, request(server, "GET", target).startsWith("HTTP/1.1 400"))
                }
                assertTrue(request(server, "GET", "/sub").startsWith("HTTP/1.1 404"))
                assertTrue(request(server, "GET", "/outside.txt").startsWith("HTTP/1.1 404"))
                assertTrue(request(server, "POST", "/").startsWith("HTTP/1.1 405"))
                assertTrue(request(server, "PUT", "/").startsWith("HTTP/1.1 405"))
            } finally { server.close() }
        }
    }

    @Test fun previewPathsAreCanonicalAndRejectMalformedOrEscapingInputs() {
        for ((root, entry, expected) in listOf(Triple("", "index.html", "index.html"),
            Triple("site", "index.html", "site/index.html"), Triple("site", "site/index.html", "site/index.html"),
            Triple("web/dist", "index.html", "web/dist/index.html"),
            Triple("web/dist", "web/dist/index.html", "web/dist/index.html"))) {
            assertTrue(PreviewPaths.parse(root, entry).entry.value == expected)
        }
        for ((root, entry) in listOf("site" to "../index.html", "site" to "site/../secret", "site" to "/index.html",
            "site" to "C:/index.html", "/site" to "index.html", "site" to "index\u0000.html",
            "site" to "sub//index.html", ".git" to "index.html", "site" to ".git/config", "" to "")) {
            val rejected = try { PreviewPaths.parse(root, entry); false } catch (_: Exception) { true }
            assertTrue("$root / $entry must be rejected", rejected)
        }
    }

    @Test fun identityChangeDeniesFurtherReads() {
        val entry = WorkspaceEntry(WorkspacePath.parse("index.html"), "entry", false,
            "text/html", 4)
        val server = LocalPreviewServer(
            WorkspacePath.ROOT, WorkspacePath.parse("index.html"),
            workspaceIsCurrent = { false },
            stat = { entry },
            copyTo = { _, _, _ -> error("must not read") },
        )
        server.start()
        try { assertTrue(request(server, "GET", "/").startsWith("HTTP/1.1 410")) }
        finally { server.close() }
    }

    @Test fun oversizedFilesAreRejectedAndContentTypesAreExplicit() {
        assertTrue(LocalPreviewServer.contentType("index.html").startsWith("text/html"))
        assertTrue(LocalPreviewServer.contentType("app.mjs").startsWith("application/javascript"))
        assertTrue(LocalPreviewServer.contentType("module.wasm") == "application/wasm")
        assertTrue(LocalPreviewServer.contentType("unknown.bin") == "application/octet-stream")
        val entry = WorkspaceEntry(WorkspacePath.parse("large.html"), "large", false,
            "text/html", LocalPreviewServer.MAX_FILE_BYTES + 1)
        val server = LocalPreviewServer(
            WorkspacePath.ROOT, WorkspacePath.parse("large.html"),
            workspaceIsCurrent = { true }, stat = { entry },
            copyTo = { _, _, _ -> error("oversized file must not be read") },
        )
        server.start()
        try { assertTrue(request(server, "GET", "/").startsWith("HTTP/1.1 413")) }
        finally { server.close() }
    }

    private fun request(server: LocalPreviewServer, method: String, path: String): String {
        return request(server.url, method, path)
    }

    private fun request(url: String, method: String, path: String, host: String = "127.0.0.1"): String {
        val port = url.substringAfterLast(':').substringBefore('/')
        return Socket("127.0.0.1", port.toInt()).use { socket ->
            socket.soTimeout = 4_000
            socket.getOutputStream().write("$method $path HTTP/1.1\r\nHost: $host\r\n\r\n"
                .toByteArray(StandardCharsets.US_ASCII))
            socket.getOutputStream().flush()
            socket.getInputStream().readBytes().toString(StandardCharsets.UTF_8)
        }
    }

    @Test fun rejectsCaseInsensitiveGitPathsAndNonLoopbackHostHeaders() {
        val files = mapOf("site/index.html" to "<h1>ok</h1>".toByteArray())
        val entries = mapOf(
            "site/index.html" to WorkspaceEntry(WorkspacePath.parse("site/index.html"), "idx", false, "text/html", 9L),
        )
        val server = LocalPreviewServer(
            WorkspacePath.parse("site"), WorkspacePath.parse("site/index.html"),
            workspaceIsCurrent = { true },
            stat = { entries[it.value] ?: error("missing") },
            copyTo = { path, output, _ ->
                val b = files.getValue(path.value)
                output.write(b)
                b.size.toLong()
            },
        )
        server.start()
        try {
            assertTrue(request(server.url, "GET", "/.GIT/config").startsWith("HTTP/1.1 400"))
            assertTrue(request(server.url, "GET", "/sub/.Git/HEAD").startsWith("HTTP/1.1 400"))
            assertTrue(request(server.url, "GET", "/", host = "evil.example").startsWith("HTTP/1.1 400"))
            assertTrue(request(server.url, "GET", "/", host = "127.0.0.1.evil.com").startsWith("HTTP/1.1 400"))
            val rejected = try { PreviewPaths.parse("site", ".GIT/config"); false } catch (_: Exception) { true }
            assertTrue(".GIT/config must be rejected by PreviewPaths", rejected)
        } finally {
            server.close()
        }
    }

    @Test fun servesBurstOfConcurrentAssetRequestsWithoutDropping() {
        val bytes = "body{}".toByteArray()
        val entries = mapOf(
            "site/index.html" to WorkspaceEntry(WorkspacePath.parse("site/index.html"), "idx", false, "text/html", bytes.size.toLong()),
            "site/app.js" to WorkspaceEntry(WorkspacePath.parse("site/app.js"), "js", false, "application/javascript", bytes.size.toLong()),
        )
        val gate = java.util.concurrent.CountDownLatch(1)
        val entered = java.util.concurrent.CountDownLatch(2)
        val server = LocalPreviewServer(
            WorkspacePath.parse("site"), WorkspacePath.parse("site/index.html"),
            workspaceIsCurrent = { true },
            stat = {
                entered.countDown()
                gate.await(3, java.util.concurrent.TimeUnit.SECONDS)
                entries[it.value] ?: error("missing")
            },
            copyTo = { _, output, _ -> output.write(bytes); bytes.size.toLong() },
        )
        server.start()
        try {
            val pool = java.util.concurrent.Executors.newFixedThreadPool(10)
            try {
                val futures = (1..10).map {
                    pool.submit(java.util.concurrent.Callable {
                        request(server.url, "GET", "/app.js")
                    })
                }
                assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS))
                Thread.sleep(100)
                gate.countDown()
                val responses = futures.map { it.get(5, java.util.concurrent.TimeUnit.SECONDS) }
                assertTrue("All 10 concurrent responses must succeed: $responses",
                    responses.all { it.startsWith("HTTP/1.1 200") })
            } finally {
                pool.shutdownNow()
            }
        } finally {
            gate.countDown()
            server.close()
        }
    }
}
