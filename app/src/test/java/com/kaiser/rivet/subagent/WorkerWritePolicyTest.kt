package com.kaiser.rivet.subagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkerWritePolicyTest {
    @Test fun anUnregisteredWriterIsOutsideScope() {
        assertEquals("outside_file_scope",
            WorkerWritePolicy.refusal(registered = false, scopeEmpty = false, outsideScope = false))
    }

    @Test fun anEmptyScopeIsReadOnlyAndNamedSo() {
        assertEquals("worker_read_only",
            WorkerWritePolicy.refusal(registered = true, scopeEmpty = true, outsideScope = false))
    }

    @Test fun aScopedWorkerOutsideItsPathsIsOutsideScope() {
        assertEquals("outside_file_scope",
            WorkerWritePolicy.refusal(registered = true, scopeEmpty = false, outsideScope = true))
    }

    @Test fun aWriteInsideTheScopeIsAllowed() {
        assertNull(WorkerWritePolicy.refusal(registered = true, scopeEmpty = false, outsideScope = false))
    }
}
