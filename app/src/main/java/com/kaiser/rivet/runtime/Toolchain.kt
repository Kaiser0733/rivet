package com.kaiser.rivet.runtime

import android.content.Context
import android.content.pm.ApplicationInfo
import android.system.Os
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The PATH a command gets today; the toolchain farm, when present, is prepended to it. */
internal const val SYSTEM_PATH = "/system/bin:/system/xbin:/vendor/bin"

/**
 * The BusyBox applets Rivet exposes through the Zone D symlink farm (plan §4.1 names the
 * shape, not the list): file, text and archive work the agent currently misses on toybox,
 * plus the process and environment basics around them. One multicall binary dispatches on
 * argv[0], so every entry here is a symlink, not a copy.
 */
internal val BUSYBOX_APPLETS: List<String> = listOf(
    // Shell and environment.
    "sh", "ash", "echo", "printf", "env", "printenv", "pwd", "true", "false", "test", "[",
    "expr", "seq", "yes", "sleep", "timeout", "nohup", "time",
    // Files and directories.
    "ls", "cat", "cp", "mv", "rm", "ln", "mkdir", "rmdir", "touch", "chmod", "chown",
    "stat", "readlink", "realpath", "basename", "dirname", "mktemp", "install", "truncate",
    "sync", "du", "df", "find", "xargs", "which",
    // Text.
    "head", "tail", "wc", "sort", "uniq", "cut", "tr", "tee", "nl", "od", "xxd", "hexdump",
    "grep", "egrep", "fgrep", "sed", "awk", "diff", "cmp", "split", "paste", "comm",
    "fold", "expand", "unexpand", "rev", "base64", "md5sum", "sha1sum", "sha256sum",
    "sha512sum", "cksum",
    // Archives and compression.
    "tar", "gzip", "gunzip", "zcat", "bzip2", "bunzip2", "bzcat", "xz", "unxz", "xzcat",
    // Processes and system facts.
    "ps", "kill", "killall", "pgrep", "pkill", "id", "whoami", "uname", "hostname", "uptime",
    "date", "busybox", "nproc", "free",
)

/** Bumped whenever `python-stdlib.zip` changes, so an app update re-extracts it once. */
internal const val PYTHON_STDLIB_VERSION = "3.12.3-rivet1"
internal const val PYTHON_MARKER = ".rivet-python-version"
internal const val SYSTEM_CA_PATH = "/system/etc/security/cacerts"
private const val PYTHON_ASSET = "python-stdlib.zip"

/**
 * The two-zone layout from plan §2.3:
 * - Zone E is the app's native library directory, where `libbusybox.so` ships;
 * - Zone D is `filesDir/toolchain/`, the data root;
 * - the symlink farm is Zone D's `bin/`, and CPython lives under Zone D's `python/`.
 */
internal class Toolchain(context: Context) {
    // Paths resolve on use, never at construction: a context that exposes no
    // applicationInfo/nativeLibraryDir (Robolectric, or a platform quirk) must behave
    // exactly like a missing binary instead of breaking the view model that owns us.
    private val app = context.applicationContext

    // Both reads can be absent in a test or an unusual platform build; a missing value
    // is the same state as a missing binary, never an error.
    private val applicationInfo: ApplicationInfo?
        get() = try { app.applicationInfo } catch (_: Exception) { null }

    private val filesDir: File?
        get() = try { app.filesDir } catch (_: Exception) { null }

    val nativeDir: File? get() = applicationInfo?.nativeLibraryDir?.let(::File)
    val busybox: File? get() = nativeDir?.let { File(it, "libbusybox.so") }
    val pythonExecutable: File? get() = nativeDir?.let { File(it, "libpython3.so") }
    val dataRoot: File? get() = filesDir?.let { File(it, "toolchain") }
    val binDir: File? get() = dataRoot?.let { File(it, "bin") }
    val pythonHome: File? get() = dataRoot?.let { File(it, "python") }
    val pythonTmp: File? get() = dataRoot?.let { File(it, "tmp") }

    @Volatile private var prepared = false
    @Volatile private var pythonReady = false

    /**
     * Builds or repairs the farm and extracts the Python stdlib once per process, off the
     * main thread. With no `libbusybox.so` and no `libpython3.so` this is a no-op and
     * [path] stays exactly as it is today.
     */
    suspend fun ensurePrepared() {
        if (prepared) return
        val farm = binDir
        if (farm == null) {
            prepared = true
            return
        }
        val binary = busybox?.takeIf { it.isFile }
        val python = pythonExecutable?.takeIf { it.isFile }
        if (binary == null && python == null) {
            prepared = true
            return
        }
        withContext(Dispatchers.IO) {
            try {
                // Plan §5 rule 10: Zone D takes no new bytes at the 1 GB cap. The farm and
                // the stdlib are the Zone D writers; installs use the same gate.
                if (!ToolchainStorage.admits(dataRoot, 0L)) {
                    prepared = true
                    return@withContext
                }
                if (python != null) {
                    // A broken stdlib must not cost the BusyBox farm or the legacy PATH;
                    // it only keeps the Python environment off for this process.
                    try {
                        preparePython()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        pythonReady = false
                    }
                }
                syncLinks(farm, farmTargets(BUSYBOX_APPLETS, binary?.absolutePath, python?.absolutePath))
                prepared = true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // A farm that cannot be built leaves PATH alone; commands still run.
            }
        }
    }

    /** The command PATH: the farm first when it exists, today's value otherwise. */
    fun path(systemPath: String = SYSTEM_PATH): String {
        val farm = binDir ?: return systemPath
        return commandPath(busybox, farm, systemPath, pythonExecutable)
    }

    /** Python's fixed environment, or the exact input when Python was not prepared. */
    fun commandEnvironment(base: List<String>): List<String> =
        if (pythonReady) pythonEnvironment(base, pythonHome, pythonTmp) else base

    /**
     * Extracts the stdlib when the marker is missing or stale, then repairs the
     * lib-dynload links. The marker is written only after the extraction succeeds, so a
     * partial tree is retried on the next launch instead of being trusted.
     */
    private fun preparePython() {
        val home = pythonHome ?: return
        val executable = pythonExecutable ?: return
        val native = executable.parentFile ?: return
        val temporary = pythonTmp ?: return
        val marker = File(home, PYTHON_MARKER)
        val manifest = File(home, "SYMLINKS.manifest")
        val stdlib = File(home, "lib/python3.12/os.py")
        val extract = pythonExtraction(readMarker(marker), PYTHON_STDLIB_VERSION,
            stdlib.isFile && manifest.isFile) == PythonExtraction.Extract
        if (extract) {
            if (!home.deleteRecursively() && home.exists()) return
            if (!extractStdlib(home)) return
            if (!manifest.isFile) return
        }
        val links = pythonLinkTargets(parsePythonManifest(manifest.readText()), native)
        if (!temporary.isDirectory && !temporary.mkdirs()) return
        syncLinks(home, links)
        if (extract) {
            try { marker.writeText(PYTHON_STDLIB_VERSION) } catch (_: Exception) { }
        }
        pythonReady = true
    }

    private fun extractStdlib(home: File): Boolean {
        return try {
            if (!home.mkdirs() && !home.isDirectory) return false
            val root = home.canonicalFile
            app.assets.open(PYTHON_ASSET).use { input ->
                ZipInputStream(BufferedInputStream(input)).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val target = File(home, entry.name).canonicalFile
                        if (target == root || target.path.startsWith(root.path + File.separator)) {
                            if (entry.isDirectory) {
                                target.mkdirs()
                            } else {
                                target.parentFile?.mkdirs()
                                FileOutputStream(target).use { output ->
                                    BufferedOutputStream(output).use { zip.copyTo(it) }
                                }
                            }
                        }
                        zip.closeEntry()
                    }
                }
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    private fun readMarker(file: File): String? = try {
        if (file.isFile) file.readText().trim() else null
    } catch (_: Exception) { null }
}

/** PATH composition, kept pure so the no-binary case is provably byte-identical. */
internal fun commandPath(busybox: File?, farm: File, systemPath: String = SYSTEM_PATH,
                         python: File? = null): String =
    if ((busybox?.isFile == true || python?.isFile == true) && farm.isDirectory) {
        "${farm.absolutePath}:$systemPath"
    } else systemPath

/** What one farm pass has to do, relative to the links already on disk. */
internal data class FarmPlan(
    val create: List<String>,
    val repair: List<String>,
    val remove: List<String>,
) {
    val isEmpty: Boolean get() = create.isEmpty() && repair.isEmpty() && remove.isEmpty()
}

/**
 * The farm's name-to-target map: every BusyBox applet plus python3/python when the
 * interpreter shipped. Missing targets leave their entries out, so the absent case is
 * exactly the old applet set.
 */
internal fun farmTargets(
    applets: List<String>,
    busybox: String?,
    python: String? = null,
): Map<String, String> {
    val targets = LinkedHashMap<String, String>()
    if (busybox != null) applets.forEach { targets[it] = busybox }
    if (python != null) {
        targets["python3"] = python
        targets["python"] = python
    }
    return targets
}

/** Compares wanted links with the links already on disk, regardless of target shape. */
internal fun planLinks(wanted: Map<String, String>, existing: Map<String, String>): FarmPlan =
    FarmPlan(
        create = wanted.keys.filterNot { it in existing },
        repair = wanted.keys.filter { name -> existing[name] != null && existing[name] != wanted[name] },
        remove = existing.keys.filterNot { it in wanted }.sorted(),
    )

/**
 * Compares the wanted applets with the links already in the farm. A link is repaired when
 * it points at anything other than the current busybox (an app update moves the native
 * library directory), and any link for an applet no longer listed is removed.
 */
internal fun planFarm(
    wanted: List<String>,
    existing: Map<String, String>,
    target: String,
    python: String? = null,
): FarmPlan = planLinks(farmTargets(wanted, target, python), existing)

/** Applies a link plan to [root]; only links are ours to remove, other entries stay. */
private fun syncLinks(root: File, wanted: Map<String, String>) {
    if (wanted.isEmpty()) return
    if (!root.isDirectory && !root.mkdirs()) return
    val existing = LinkedHashMap<String, String>()
    val directories = wanted.keys.mapNotNull { File(root, it).parentFile }.toSet()
    for (directory in directories) {
        directory.listFiles()?.forEach { child ->
            try {
                existing[child.relativeTo(root).path] = Os.readlink(child.absolutePath)
            } catch (_: Exception) {
                // A real file or directory is not one of our links.
            }
        }
    }
    val plan = planLinks(wanted, existing)
    for (name in plan.remove) File(root, name).delete()
    for (name in plan.create + plan.repair) {
        val link = File(root, name)
        link.parentFile?.mkdirs()
        link.delete()
        try {
            Os.symlink(wanted.getValue(name), link.absolutePath)
        } catch (_: Exception) {
            // One link that cannot be made must not stop the rest.
        }
    }
}

/** One line of `zone-d/SYMLINKS.manifest`: a Zone D path and its Zone E target name. */
internal data class PythonLink(val path: String, val target: String)

/**
 * Parses the manifest's `<zone_d_relative_path> <zone_e_target_name>` lines. Comments and
 * blank lines are skipped; absolute paths, `..` escapes and targets with a separator are
 * dropped, so a malformed manifest can never link outside the app's own directories.
 */
internal fun parsePythonManifest(text: String): List<PythonLink> {
    val links = ArrayList<PythonLink>()
    for (raw in text.lineSequence()) {
        val line = raw.substringBefore('#').trim()
        if (line.isEmpty()) continue
        val parts = line.split(Regex("\\s+"), limit = 2)
        if (parts.size != 2) continue
        val path = parts[0]
        val target = parts[1]
        if (path.isEmpty() || path.startsWith("/") || path.split('/').any { it == ".." }) continue
        if (target.isEmpty() || target == "." || target == ".." || '/' in target) continue
        links += PythonLink(path, target)
    }
    return links
}

/** Resolves manifest targets against the app's current native library directory. */
internal fun pythonLinkTargets(links: List<PythonLink>, nativeDir: File): Map<String, String> =
    links.associate { it.path to File(nativeDir, it.target).absolutePath }

internal enum class PythonExtraction { Extract, Current }

/** Fresh (no marker) and stale markers extract; a current marker with the tree present is a no-op. */
internal fun pythonExtraction(
    marker: String?,
    version: String,
    stdlibPresent: Boolean,
): PythonExtraction =
    if (stdlibPresent && marker == version) PythonExtraction.Current else PythonExtraction.Extract

/**
 * Adds PYTHONHOME, SSL_CERT_FILE and the Zone D TMPDIR to the command environment. A null
 * home means Python was not prepared, and the input list is returned unchanged.
 */
internal fun pythonEnvironment(base: List<String>, home: File?, temporary: File?): List<String> {
    if (home == null || temporary == null) return base
    val environment = ArrayList<String>(base.size + 3)
    var temporaryReplaced = false
    for (entry in base) {
        if (entry.startsWith("TMPDIR=")) {
            if (!temporaryReplaced) environment += "TMPDIR=${temporary.absolutePath}"
            temporaryReplaced = true
        } else {
            environment += entry
        }
    }
    if (!temporaryReplaced) environment += "TMPDIR=${temporary.absolutePath}"
    environment += "PYTHONHOME=${home.absolutePath}"
    environment += "SSL_CERT_FILE=$SYSTEM_CA_PATH"
    return environment
}
