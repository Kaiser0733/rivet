package com.kaiser.rivet.agent

import java.io.File
import java.security.MessageDigest

/**
 * Content identity for a bundled skill script. Approvals are tied to the bytes the user
 * reviewed, not to the skill's name, so replacing a script invalidates the old grant.
 */
internal object SkillScriptContent {
    private const val MAX_FILES = 128
    private const val MAX_BYTES = 4L * 1024 * 1024

    suspend fun hash(hit: SkillScriptHit, workspace: AgentWorkspace): String? {
        val path = hit.path.replace('\\', '/').trim().removePrefix("./")
        if (path.isEmpty()) return null
        val candidates = buildList {
            add(path)
            hit.skill.source?.baseDir?.let { base ->
                if (!path.startsWith("$base/")) add("$base/$path".trimStart('/'))
            }
        }.distinct()
        for (candidate in candidates) {
            if (candidate.startsWith('/')) {
                val file = File(candidate)
                if (file.exists()) return hashFile(file)
            }
            val snapshot = try { workspace.read(candidate) }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { null }
            if (snapshot != null) return hashBytes(snapshot.text.toByteArray(Charsets.UTF_8))
            val entries = try { workspace.list(candidate) }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { null }
            if (entries != null) {
                val digest = MessageDigest.getInstance("SHA-256")
                var files = 0
                var bytes = 0L
                suspend fun walk(base: String): Boolean {
                    val children = try { workspace.list(base) }
                        catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (_: Exception) { return false }
                    for (entry in children.sortedBy { it.path }) {
                        if (entry.directory) {
                            if (!walk(entry.path)) return false
                        } else {
                            val child = try { workspace.read(entry.path) }
                                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                                catch (_: Exception) { return false }
                            val content = child.text.toByteArray(Charsets.UTF_8)
                            files++
                            bytes += content.size
                            if (files > MAX_FILES || bytes > MAX_BYTES) return false
                            val relative = entry.path.removePrefix(candidate).trimStart('/')
                            digest.update(relative.toByteArray(Charsets.UTF_8))
                            digest.update(0)
                            digest.update(content)
                            digest.update(0)
                        }
                    }
                    return true
                }
                if (walk(candidate)) return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            }
        }
        return null
    }

    private fun hashFile(file: File): String? {
        if (file.isDirectory) {
            var files = 0
            var bytes = 0L
            val digest = MessageDigest.getInstance("SHA-256")
            val children = file.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(file).invariantSeparatorsPath }
            for (child in children) {
                val content = try { child.readBytes() } catch (_: Exception) { return null }
                files++
                bytes += content.size
                if (files > MAX_FILES || bytes > MAX_BYTES) return null
                digest.update(child.relativeTo(file).invariantSeparatorsPath.toByteArray(Charsets.UTF_8))
                digest.update(0)
                digest.update(content)
                digest.update(0)
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        val content = try { file.readBytes() } catch (_: Exception) { return null }
        return hashBytes(content)
    }

    private fun hashBytes(content: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(content)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
