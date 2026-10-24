package com.kaiser.rivet.provider

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatGptSignInClientIdTest {

    @Test fun bundleBeatsPendingBeatsDynamic() {
        assertEquals(
            "oaiapp_bundle",
            resolveSignInClientId("oaiapp_bundle", "oaiapp_pending"),
        )
        assertEquals(
            "oaiapp_pending",
            resolveSignInClientId(null, "oaiapp_pending"),
        )
        assertEquals(
            CHATGPT_DYNAMIC_CLIENT_ID,
            resolveSignInClientId(null, null),
        )
    }

    @Test fun blankIdsAreTreatedAsAbsent() {
        assertEquals(
            "oaiapp_pending",
            resolveSignInClientId("", "oaiapp_pending"),
        )
        assertEquals(
            "oaiapp_pending",
            resolveSignInClientId("   ", "oaiapp_pending"),
        )
        assertEquals(
            CHATGPT_DYNAMIC_CLIENT_ID,
            resolveSignInClientId(null, ""),
        )
    }
}
