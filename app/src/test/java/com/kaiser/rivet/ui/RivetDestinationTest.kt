package com.kaiser.rivet.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class RivetDestinationTest {
    @Test fun terminalAndWorkerBackReturnToTools() {
        assertEquals(RivetDestination.Tools, RivetDestination.Terminal.backDestination())
        assertEquals(RivetDestination.Tools, RivetDestination.Workers.backDestination())
    }

    @Test fun modelsAndSkillInstallRememberWhereTheyWereOpened() {
        for (destination in listOf(RivetDestination.Models, RivetDestination.SkillInstall)) {
            assertEquals(RivetDestination.Chat, destination.backDestination())
            assertEquals(RivetDestination.Settings, destination.backDestination(RivetDestination.Settings))
        }
    }
}
