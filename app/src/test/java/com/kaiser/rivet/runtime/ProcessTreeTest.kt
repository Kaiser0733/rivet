package com.kaiser.rivet.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessTreeTest {
    @Test fun nestedTreesIncludeEveryDescendantAndNothingElse() {
        val parents = mapOf(
            100 to 1,   // command leader
            101 to 100, // child
            102 to 100, // second child
            103 to 101, // grandchild
            104 to 103, // great-grandchild
            200 to 1,   // unrelated tree
            201 to 200,
        )

        assertEquals(setOf(101, 102, 103, 104), descendants(100, parents))
    }

    @Test fun anEscapedSubtreeIsNotADescendant() {
        // A setsid'd child reparented to init can no longer be reached from the root.
        val parents = mapOf(100 to 1, 101 to 100, 102 to 1, 103 to 102)

        assertEquals(setOf(101), descendants(100, parents))
    }

    @Test fun cyclesInBadDataTerminateAndNeverReportTheRoot() {
        val parents = mapOf(100 to 102, 101 to 100, 102 to 101)

        assertEquals(setOf(101, 102), descendants(100, parents))
    }

    @Test fun selfParentEntriesAndSiblingTreesStayOut() {
        val parents = mapOf(100 to 1, 101 to 101, 102 to 100, 200 to 1, 201 to 200)

        assertEquals(setOf(102), descendants(100, parents))
    }

    @Test fun capAllowsSixtyFourDescendantsAndRejectsSixtyFive() {
        val atCap = (1..MAX_DESCENDANTS).associate { (1000 + it) to 100 }
        val overCap = (1..MAX_DESCENDANTS + 1).associate { (1000 + it) to 100 }

        assertEquals(MAX_DESCENDANTS, descendants(100, atCap).size)
        assertFalse(overDescendantCap(100, atCap))
        assertTrue(overDescendantCap(100, overCap))
    }

    @Test fun sessionCapCountsChildrenButNotTheLeader() {
        val children = (1..MAX_DESCENDANTS).map { 1_000 + it }.toSet()

        assertFalse(overSessionCap(100, children + 100))
        assertTrue(overSessionCap(100, children + setOf(100, 1_065)))
    }

    @Test fun pssParsersReadRollupAndStatusFields() {
        val rollup = "Rss:             1000 kB\nPss:              742 kB\nPss_Anon:         500 kB\n"
        assertEquals(742L, parseSmapsRollupPssKb(rollup))
        val status = "Name:\tsh\nVmPeak:\t1000 kB\nVmRSS:\t       321 kB\n"
        assertEquals(321L, parseStatusVmRssKb(status))
        assertNull(parseSmapsRollupPssKb("no memory fields"))
        assertNull(parseStatusVmRssKb("Pss: 1 kB"))
    }

    @Test fun treePssSumsKnownMembersAndSkipsMissingOnes() {
        val readings = mapOf(100 to 10L, 101 to 20L)

        assertEquals(30L * 1024L, pssBytes(readings, setOf(100, 101, 102)))
    }

    @Test fun statParsersReadPastACommWithSpacesAndParens() {
        val stat = "123 (a (b) c) S 42 7 7 0 -1 4194560 1 0 0 0 0 0 0 0 20 0 1 0 987654 0 0"

        assertEquals(42, parseProcStatPpid(stat))
        assertEquals(7, parseProcStatSession(stat))
        assertEquals(987654L, parseProcStatStartTime(stat))
        assertNull(parseProcStatPpid("not a stat line"))
    }
}
