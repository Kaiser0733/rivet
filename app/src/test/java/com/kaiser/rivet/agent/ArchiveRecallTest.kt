package com.kaiser.rivet.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveRecallTest {
    @Test fun referencesToPriorFactsRequireRecall() {
        assertTrue(ArchiveRecall.required("What was the code word I gave you?"))
        assertTrue(ArchiveRecall.required("What did I say earlier?"))
        assertFalse(ArchiveRecall.required("Remember this number for next time."))
    }

    @Test fun expandedPhrasesUsePortableFts4QuerySyntax() {
        assertEquals(listOf("\"code\" \"word\"", "\"number\""),
            ArchiveRecall.matchQueries(listOf("code word", "the number", "code word")))
    }

    @Test fun theAnswerIsAnExactQuoteOrAnExplicitMiss() {
        val candidate = "The code word is monsoon-42 and the number is 5000."
        val hit = ArchiveWindow("You", 1, 0, ArchiveRecall.sentence("Hi. $candidate", listOf("code word")), emptyList())
        assertEquals("From you, turn 1:\n\n$candidate", ArchiveRecall.directAnswer(listOf(hit)))
        assertEquals(ArchiveRecall.NOT_FOUND, ArchiveRecall.directAnswer(emptyList()))
    }

    @Test fun pinsGoImmediatelyBeforeTheRealUserMessage() {
        val prior = AgentMessage.user("Checkpoint")
        val user = AgentMessage.user("What's next?")
        val lateContext = AgentMessage.user("Project guidance")
        val result = ArchiveRecall.splice(listOf(prior, user, lateContext), "Turn 1", user)
        assertSame(prior, result[0])
        assertTrue(result[1].text.endsWith("Turn 1"))
        assertSame(user, result[2])
        assertSame(lateContext, result[3])
    }
}
