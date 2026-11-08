package com.kaiser.rivet.runtime

import android.content.Context
import android.content.pm.ApplicationInfo
import android.system.Os
import java.io.File
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

/**
 * The two-zone layout from plan §2.3:
 * - Zone E is the app's native library directory, where `libbusybox.so` ships;
 * - Zone D is `filesDir/toolchain/`, the data root;
 * - the symlink farm is Zone D's `bin/`.
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
    val dataRoot: File? get() = filesDir?.let { File(it, "toolchain") }
    val binDir: File? get() = dataRoot?.let { File(it, "bin") }

    @Volatile private var prepared = false

    /**
     * Builds or repairs the farm once per process, off the main thread. With no
     * `libbusybox.so` this is a no-op and [path] stays exactly as it is today.
     */
    suspend fun ensurePrepared() {
        if (prepared) return
        val binary = busybox
        val farm = binDir
        if (binary == null || farm == null || !binary.isFile) {
            prepared = true
            return
        }
        withContext(Dispatchers.IO) {
            try {
                // Plan §5 rule 10: Zone D takes no new bytes at the 1 GB cap. The farm is
                // today's only Zone D writer; installs in later steps use the same gate.
                if (!ToolchainStorage.admits(dataRoot, 0L)) {
                    prepared = true
                    return@withContext
                }
                syncFarm(farm, binary, BUSYBOX_APPLETS)
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
        val binary = busybox ?: return systemPath
        val farm = binDir ?: return systemPath
        return commandPath(binary, farm, systemPath)
    }
}

/** PATH composition, kept pure so the no-binary case is provably byte-identical. */
internal fun commandPath(busybox: File, farm: File, systemPath: String = SYSTEM_PATH): String =
    if (busybox.isFile && farm.isDirectory) "${farm.absolutePath}:$systemPath" else systemPath

/** What one farm pass has to do, relative to the links already on disk. */
internal data class FarmPlan(
    val create: List<String>,
    val repair: List<String>,
    val remove: List<String>,
) {
    val isEmpty: Boolean get() = create.isEmpty() && repair.isEmpty() && remove.isEmpty()
}

/**
 * Compares the wanted applets with the links already in the farm. A link is repaired when
 * it points at anything other than the current busybox (an app update moves the native
 * library directory), and any link for an applet no longer listed is removed.
 */
internal fun planFarm(
    wanted: List<String>,
    existing: Map<String, String>,
    target: String,
): FarmPlan {
    val wantedSet = wanted.toSet()
    return FarmPlan(
        create = wanted.filterNot { it in existing },
        repair = wanted.filter { existing[it]?.let { link -> link != target } == true },
        remove = existing.keys.filterNot { it in wantedSet }.sorted(),
    )
}

/** Applies [planFarm] to the disk; only links are ours to remove, other entries stay. */
private fun syncFarm(farm: File, busybox: File, applets: List<String>) {
    if (!farm.isDirectory && !farm.mkdirs()) return
    val existing = LinkedHashMap<String, String>()
    farm.listFiles()?.forEach { child ->
        try {
            existing[child.name] = Os.readlink(child.absolutePath)
        } catch (_: Exception) {
            // A real file or directory in the farm is not one of our links.
        }
    }
    val plan = planFarm(applets, existing, busybox.absolutePath)
    for (name in plan.remove) File(farm, name).delete()
    for (name in plan.create + plan.repair) {
        val link = File(farm, name)
        link.delete()
        try {
            Os.symlink(busybox.absolutePath, link.absolutePath)
        } catch (_: Exception) {
            // One applet that cannot be linked must not stop the rest.
        }
    }
}
