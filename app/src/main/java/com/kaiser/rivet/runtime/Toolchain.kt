package com.kaiser.rivet.runtime

import android.content.Context
import android.content.pm.ApplicationInfo
import android.system.Os
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
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

/** Bumped with the bundled Mozilla extract so an app update refreshes it. */
internal const val CA_BUNDLE_VERSION = "mozilla-cacert-2026-11-09"
internal const val CA_BUNDLE_MARKER = ".rivet-ca-version"
private const val CA_BUNDLE_ASSET = "ca-certificates.pem"

/** Bumped whenever `git-templates.zip` changes, so an app update re-extracts it once. */
internal const val GIT_TEMPLATES_VERSION = "2.56.0-rivet1"
internal const val GIT_TEMPLATES_MARKER = ".git-templates-version"
private const val GIT_TEMPLATES_ASSET = "git-templates.zip"

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
    val pythonBinDir: File? get() = pythonHome?.let { File(it, "bin") }
    val pythonTmp: File? get() = dataRoot?.let { File(it, "tmp") }
    val git: File? get() = nativeDir?.let { File(it, "libgit.so") }
    val gitRemoteHttps: File? get() = nativeDir?.let { File(it, "libgit-remote-https.so") }
    val pip: File? get() = nativeDir?.let { File(it, "libpip.so") }
    val gitExecDir: File? get() = dataRoot?.let { File(it, "libexec/git-core") }
    val gitTemplateDir: File? get() = dataRoot?.let { File(it, "share/git-core/templates") }
    val caBundle: File? get() = dataRoot?.let { File(it, "etc/ssl/cert.pem") }

    /** Which shipped toolchain pieces are actually on disk; the prompt and Settings
     * copy are composed from this rather than from what the app intended to ship. */
    fun availability(): ToolchainAvailability = ToolchainAvailability(
        busybox = busybox?.isFile == true,
        python = pythonExecutable?.isFile == true,
        git = git?.isFile == true,
        writableEntryPoints = entryAudit?.violations?.map { it.name } ?: emptyList(),
    )

    @Volatile private var prepared = false
    @Volatile private var pythonReady = false
    @Volatile private var entryAudit: ToolchainEntryAuditReport? = null

    /** True once the git binary was present and its farm/templates were prepared. */
    @Volatile var gitReady: Boolean = false
        private set

    /**
     * Builds or repairs the farms, extracts the Python stdlib and git templates once per
     * process, off the main thread. With none of the three binaries this is a no-op and
     * [path] plus the command environment stay exactly as they are today.
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
        val gitBinary = git?.takeIf { it.isFile }
        val gitRemote = gitRemoteHttps?.takeIf { it.isFile }
        withContext(Dispatchers.IO) {
            try {
                // Plan §5 rule 10: Zone D takes no new bytes at the 1 GB cap. The farm and
                // the stdlib are the Zone D writers; installs use the same gate.
                if (!ToolchainStorage.admits(dataRoot, 0L)) {
                    prepared = true
                    return@withContext
                }
                // The CA file is independent of the binaries; extract it even when a
                // device is missing the toolchain so the next update can use it.
                try {
                    prepareCaBundle()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) { }
                if (binary == null && python == null && gitBinary == null) {
                    // The module ships arm64 binaries; a build or device where none is visible
                    // must not silently pretend the toolchain exists.
                    Log.w("Toolchain", "No toolchain binaries under " +
                        (nativeDir?.absolutePath ?: "an unavailable native directory") +
                        "; commands use the legacy shell environment.")
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
                if (gitBinary != null) {
                    // A failed template extraction must not cost local git; only the
                    // missing templates are absent, and the next process retries them.
                    try {
                        prepareGit(gitBinary, gitRemote)
                        gitReady = true
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        gitReady = false
                    }
                }
                val pipBinary = pip?.takeIf { it.isFile }
                syncLinks(farm, farmTargets(BUSYBOX_APPLETS, binary?.absolutePath,
                    python?.absolutePath, gitBinary?.absolutePath, pipBinary?.absolutePath))
                if (pythonReady) preparePip()
                entryAudit = ToolchainEntryAudit.audit(
                    farmLinks = publishedLinks(farm),
                    pythonBinLinks = publishedLinks(pythonBinDir),
                    pythonBinOnPath = path().split(':').contains(pythonBinDir?.absolutePath),
                    gitExecLinks = publishedLinks(gitExecDir),
                    nativeDir = nativeDir?.absolutePath.orEmpty(),
                )
                entryAudit?.violations?.takeIf { it.isNotEmpty() }?.let { violations ->
                    Log.e("Toolchain", "Writable user-invocable toolchain entries: " +
                        violations.joinToString { "${it.location}/${it.name}" })
                }
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
        return commandPath(busybox, farm, systemPath, pythonExecutable, git)
    }

    /** Toolchain environment; the exact input when neither Python nor git is ready. */
    fun commandEnvironment(base: List<String>, credentialFile: File? = null): List<String> {
        val certificates = caBundle?.takeIf { it.isFile && it.length() > 0 }
        val withPython = if (pythonReady) {
            pythonEnvironment(base, pythonHome, pythonTmp, certificates)
        } else base
        return if (gitReady) {
            gitEnvironment(withPython, gitExecDir, gitTemplateDir, credentialFile, certificates)
        } else withPython
    }

    /** Copies the Mozilla extract once per asset version into Zone D. */
    private fun prepareCaBundle() {
        val target = caBundle ?: return
        val parent = target.parentFile ?: return
        val marker = File(parent, CA_BUNDLE_MARKER)
        if (target.isFile && target.length() > 0 &&
            readMarker(marker) == CA_BUNDLE_VERSION) return
        app.assets.open(CA_BUNDLE_ASSET).use { input ->
            if (!extractCaBundle(input, target)) return
        }
        marker.writeText(CA_BUNDLE_VERSION)
    }

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
        val extract = versionedExtraction(readMarker(marker), PYTHON_STDLIB_VERSION,
            stdlib.isFile && manifest.isFile) == VersionedExtraction.Extract
        if (extract) {
            if (!home.deleteRecursively() && home.exists()) return
            if (!extractZipAsset(PYTHON_ASSET, home)) return
            if (!manifest.isFile) return
        }
        val links = pythonLinkTargets(parsePythonManifest(manifest.readText()), native)
        if (!temporary.isDirectory && !temporary.mkdirs()) return
        syncLinks(home, links)
        markToolchainExecutables(home)
        if (extract) {
            try { marker.writeText(PYTHON_STDLIB_VERSION) } catch (_: Exception) { }
        }
        pythonReady = true
    }

    /**
     * `--without-ensurepip` means the stdlib carries the ensurepip module and its bundled
     * wheel but no `python/bin/pip` scripts. Bootstrap once so `python3 -m pip` works; the
     * generated scripts stay off PATH and are never exec'd directly. The network is never
     * touched: the wheel is bundled.
     */
    private fun preparePip() {
        val home = pythonHome ?: return
        val executable = pythonExecutable?.takeIf { it.isFile } ?: return
        val temporary = pythonTmp ?: return
        if (File(home, "bin/pip").isFile) return
        try {
            val base = baseEnvironment(home, path(), temporary, home)
            val env = pythonEnvironment(base, home, temporary,
                caBundle?.takeIf { it.isFile && it.length() > 0 })
            val process = ProcessBuilder(executable.absolutePath, "-m", "ensurepip", "--default-pip")
                .directory(temporary)
                .redirectErrorStream(true)
                .apply {
                    environment().clear()
                    env.forEach { entry ->
                        val split = entry.indexOf('=')
                        if (split > 0) environment()[entry.substring(0, split)] = entry.substring(split + 1)
                    }
                }
                .start()
            val outputTail = StringBuilder()
            val reader = Thread({
                try {
                    process.inputStream.bufferedReader().use { input ->
                        val buffer = CharArray(2048)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            outputTail.append(buffer, 0, count)
                            if (outputTail.length > 4096) {
                                outputTail.delete(0, outputTail.length - 4096)
                            }
                        }
                    }
                } catch (_: Exception) { }
            }, "rivet-ensurepip-drain").apply {
                isDaemon = true
                start()
            }
            val finished = process.waitFor(60, TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly()
            reader.join(1_000)
            val output = outputTail.toString()
            if (!finished) {
                Log.w("Toolchain", "ensurepip timed out; python has no pip scripts")
            } else if (process.exitValue() != 0) {
                Log.w("Toolchain", "ensurepip failed (${process.exitValue()}): ${output.takeLast(400)}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("Toolchain", "ensurepip could not run: ${e.javaClass.simpleName}")
        }
    }

    /** Extracts the templates tree and records its version, once per asset version. The
     * marker sits beside the tree, not inside it, so git never copies it into a new repo. */
    private fun prepareGitTemplates(directory: File) {
        val parent = directory.parentFile ?: return
        val marker = File(parent, GIT_TEMPLATES_MARKER)
        val description = File(directory, "description")
        val extract = versionedExtraction(readMarker(marker), GIT_TEMPLATES_VERSION,
            description.isFile) == VersionedExtraction.Extract
        if (!extract) return
        if (!directory.deleteRecursively() && directory.exists()) return
        if (!extractZipAsset(GIT_TEMPLATES_ASSET, directory)) return
        if (!description.isFile) return
        try { marker.writeText(GIT_TEMPLATES_VERSION) } catch (_: Exception) { }
    }

    private fun prepareGit(gitBinary: File, gitRemote: File?) {
        val exec = gitExecDir ?: return
        val templates = gitTemplateDir ?: return
        prepareGitTemplates(templates)
        syncLinks(exec, gitExecTargets(gitBinary.absolutePath, gitRemote?.absolutePath))
    }

    private fun extractZipAsset(asset: String, home: File): Boolean {
        return try {
            if (!home.mkdirs() && !home.isDirectory) return false
            val root = home.canonicalFile
            app.assets.open(asset).use { input ->
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

/** The non-toolchain environment: one seam so app-owned values are pinned and no
 * device profile or developer shell config can smuggle in a Termux prefix. */
internal fun baseEnvironment(
    home: File,
    path: String,
    temporary: File,
    cwd: File,
): List<String> = listOf(
    "HOME=${home.absolutePath}",
    "PATH=$path",
    "TMPDIR=${temporary.absolutePath}",
    "PWD=${cwd.absolutePath}",
    "LANG=C.UTF-8",
    "TERM=xterm-256color",
)

/** CPython and git scripts may arrive with the archive's mode; audit the tree's bin. */
internal fun markToolchainExecutables(home: File): Int {
    var changed = 0
    val bin = File(home, "bin")
    if (!bin.isDirectory) return changed
    for (file in bin.listFiles().orEmpty()) {
        if (!file.isFile) continue
        if (file.name.startsWith("python") || file.name == "git" ||
            file.name.startsWith("git-")) {
            if (file.setExecutable(true, false)) changed++
        }
    }
    return changed
}

internal data class ToolchainAvailability(
    val busybox: Boolean = false,
    val python: Boolean = false,
    val git: Boolean = false,
    val writableEntryPoints: List<String> = emptyList(),
) {
    val any: Boolean get() = busybox || python || git
}

/** PATH composition, kept pure so the no-binary case is provably byte-identical. */
internal fun commandPath(busybox: File?, farm: File, systemPath: String = SYSTEM_PATH,
                         python: File? = null, git: File? = null): String =
    if ((busybox?.isFile == true || python?.isFile == true || git?.isFile == true) &&
        farm.isDirectory) {
        "$farm:$systemPath"
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
 * The farm's name-to-target map: every BusyBox applet plus python3/python and git when
 * those binaries shipped. Missing targets leave their entries out, so the absent case is
 * exactly the old applet set.
 */
internal fun farmTargets(
    applets: List<String>,
    busybox: String?,
    python: String? = null,
    git: String? = null,
    pip: String? = null,
): Map<String, String> {
    val targets = LinkedHashMap<String, String>()
    if (busybox != null) applets.forEach { targets[it] = busybox }
    if (python != null) {
        targets["python3"] = python
        targets["python"] = python
    }
    if (git != null) targets["git"] = git
    if (pip != null) {
        targets["pip"] = pip
        targets["pip3"] = pip
    }
    return targets
}

/** GIT_EXEC_PATH entries: the smart HTTPS helpers and the local-transport builtins. */
internal fun gitExecTargets(git: String?, remoteHttps: String?): Map<String, String> {
    val targets = LinkedHashMap<String, String>()
    if (remoteHttps != null) {
        targets["git-remote-https"] = remoteHttps
        targets["git-remote-http"] = remoteHttps
    }
    if (git != null) {
        targets["git-upload-pack"] = git
        targets["git-receive-pack"] = git
        targets["git-upload-archive"] = git
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
    git: String? = null,
    pip: String? = null,
): FarmPlan = planLinks(farmTargets(wanted, target, python, git, pip), existing)

/** Every published name and its current target, including real files that are not links. */
private fun publishedLinks(root: File?): Map<String, String> {
    if (root == null || !root.isDirectory) return emptyMap()
    val links = LinkedHashMap<String, String>()
    for (child in root.listFiles().orEmpty()) {
        links[child.name] = try { Os.readlink(child.absolutePath) }
        catch (_: Exception) { child.absolutePath }
    }
    return links
}

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

internal enum class VersionedExtraction { Extract, Current }

/** Fresh (no marker) and stale markers extract; a current marker with the tree present is a no-op. */
internal fun versionedExtraction(
    marker: String?,
    version: String,
    contentPresent: Boolean,
): VersionedExtraction =
    if (contentPresent && marker == version) VersionedExtraction.Current else VersionedExtraction.Extract

/**
 * Adds PYTHONHOME, SSL_CERT_FILE and the Zone D TMPDIR to the command environment. A null
 * home means Python was not prepared, and the input list is returned unchanged.
 */
/** Copy one certificate bundle; marker write stays with the caller. */
internal fun extractCaBundle(input: InputStream, target: File): Boolean {
    return try {
        val parent = target.parentFile ?: return false
        if (!parent.isDirectory && !parent.mkdirs()) return false
        FileOutputStream(target).use { output -> input.copyTo(output) }
        target.length() > 0
    } catch (_: Exception) {
        false
    }
}

internal fun pythonEnvironment(
    base: List<String>,
    home: File?,
    temporary: File?,
    certificates: File? = null,
): List<String> {
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
    environment.removeAll { it.startsWith("SSL_CERT_FILE=") }
    environment += "SSL_CERT_FILE=${certificates?.absolutePath ?: SYSTEM_CA_PATH}"
    return environment
}

/**
 * Git's fixed environment, plus the credential-helper spec when a token is connected.
 * The spec names the helper and the app-private file; the token itself stays in that file
 * and never enters the environment.
 */
internal fun gitEnvironment(
    base: List<String>,
    execPath: File?,
    templateDir: File?,
    credentialFile: File?,
    certificates: File? = null,
): List<String> {
    if (execPath == null || templateDir == null) return base
    val environment = ArrayList<String>(base.size + 9)
    environment += base
    environment += "GIT_EXEC_PATH=${execPath.absolutePath}"
    environment += "GIT_TEMPLATE_DIR=${templateDir.absolutePath}"
    environment += "GIT_SSL_CAPATH=$SYSTEM_CA_PATH"
    environment += "GIT_CONFIG_NOSYSTEM=1"
    if (certificates != null) {
        environment.removeAll { it.startsWith("SSL_CERT_FILE=") }
        environment += "GIT_SSL_CAINFO=${certificates.absolutePath}"
        environment += "SSL_CERT_FILE=${certificates.absolutePath}"
    }
    if (credentialFile != null) {
        environment += "GIT_CONFIG_COUNT=1"
        environment += "GIT_CONFIG_KEY_0=credential.helper"
        environment += "GIT_CONFIG_VALUE_0=store --file=${credentialFile.absolutePath}"
    }
    return environment
}
