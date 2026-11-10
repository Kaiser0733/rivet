package com.kaiser.rivet.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserDomainTrailTest {
    @Test fun trailRecordsHostsInOrderAndDistinctHosts() {
        val trail = BrowserDomainTrail(maxEntries = 3)
        trail.record("https://docs.example.com/a")
        trail.record("https://github.com/x")
        trail.record("https://docs.example.com/b")
        assertEquals(listOf("docs.example.com", "github.com", "docs.example.com"), trail.visits.map { it.host })
        assertEquals(listOf("docs.example.com", "github.com"), trail.hosts())
    }

    @Test fun loopbackPreviewIsLabelledNotTreatedAsAHost() {
        val trail = BrowserDomainTrail()
        val visit = trail.record("http://127.0.0.1:8123/", projectPreview = true)!!
        assertTrue(visit.projectPreview)
        assertEquals("project preview", visit.host)
    }
}
