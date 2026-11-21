package com.kaiser.rivet.ui.history

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatRecencyTest {
    @Test fun choosesRelativeTimeAtEveryAge() {
        val now = 90L * 86_400_000
        assertEquals(ChatRecency(RecencyUnit.Now, 0), chatRecency(now + 60_000, now))
        assertEquals(ChatRecency(RecencyUnit.Now, 0), chatRecency(now - 59_999, now))
        assertEquals(ChatRecency(RecencyUnit.Minute, 1), chatRecency(now - 60_000, now))
        assertEquals(ChatRecency(RecencyUnit.Hour, 1), chatRecency(now - 3_600_000, now))
        assertEquals(ChatRecency(RecencyUnit.Day, 1), chatRecency(now - 86_400_000, now))
        assertEquals(ChatRecency(RecencyUnit.Day, 90), chatRecency(0, now))
    }
}
