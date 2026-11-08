package com.kaiser.rivet.runtime

import android.system.Os
import android.system.OsConstants
import java.io.File

/** Fork-bomb guard: at most this many descendants per command tree (plan §5 rule 6). */
internal const val MAX_DESCENDANTS = 64

/**
 * Every pid that descends from [root] in the injected pid -> ppid data, root excluded.
 * The visited set makes a cycle in bad data terminate; the root is never reported even
 * when bad data puts it inside the cycle.
 */
internal fun descendants(root: Int, parents: Map<Int, Int>): Set<Int> {
    val children = HashMap<Int, MutableList<Int>>()
    for ((pid, ppid) in parents) {
        if (pid != ppid) children.getOrPut(ppid) { mutableListOf() } += pid
    }
    val visited = HashSet<Int>()
    visited += root
    val queue = ArrayDeque<Int>()
    queue += root
    while (queue.isNotEmpty()) {
        for (child in children[queue.removeFirst()].orEmpty()) {
            if (visited.add(child)) queue += child
        }
    }
    visited -= root
    return visited
}

/** True when the tree under [root] has more descendants than the cap allows. */
internal fun overDescendantCap(root: Int, parents: Map<Int, Int>): Boolean =
    descendants(root, parents).size > MAX_DESCENDANTS

/** The ppid field (4) of /proc/<pid>/stat, after a comm that may contain spaces/parens. */
internal fun parseProcStatPpid(stat: String): Int? =
    parseStatField(stat, 1)?.toIntOrNull()

/** The starttime field (22) of /proc/<pid>/stat; identifies a pid across reuse. */
internal fun parseProcStatStartTime(stat: String): Long? =
    parseStatField(stat, 19)?.toLongOrNull()

/** Field index counted after "comm) state", so 0 is state, 1 is ppid, 19 is starttime. */
private fun parseStatField(stat: String, index: Int): String? {
    val close = stat.lastIndexOf(')')
    if (close < 0) return null
    val fields = stat.substring(close + 1).trim().split(' ')
    return fields.getOrNull(index)?.takeIf { it.isNotEmpty() }
}

/**
 * Thin impure shell: reads /proc and signals pids. Every decision (tree shape, count,
 * cap) stays in the pure functions above so it is testable on a plain JVM.
 */
internal object ProcTree {
    /** pid -> ppid for every /proc entry this UID can read. */
    fun parents(): Map<Int, Int> {
        val names = File("/proc").list() ?: return emptyMap()
        val parents = HashMap<Int, Int>()
        for (name in names) {
            val pid = name.toIntOrNull() ?: continue
            val stat = statOf(name) ?: continue
            parseProcStatPpid(stat)?.let { parents[pid] = it }
        }
        return parents
    }

    /** Start time of [pid], or null when it has already exited. */
    fun startTime(pid: Int): Long? = statOf(pid.toString())?.let(::parseProcStatStartTime)

    fun kill(pid: Int) {
        try { Os.kill(pid, OsConstants.SIGKILL) } catch (_: Exception) { }
    }

    private fun statOf(name: String): String? =
        try { File("/proc/$name/stat").readText() } catch (_: Exception) { null }
}
