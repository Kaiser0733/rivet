package com.kaiser.rivet.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectGoalWatcherTest {
    @Test fun switchingFromAGoalToANewProjectDoesNotReloadAnAbsentFile() {
        val watcher = ProjectGoalWatcher()
        watcher.remember("old-project", "Goal in another project")

        assertFalse(watcher.observe("new-project", null))
        assertFalse(watcher.observe("new-project", null))
    }

    @Test fun aGoalAppearingOrChangingInTheSameProjectIsAnExternalEdit() {
        val watcher = ProjectGoalWatcher()
        assertFalse(watcher.observe("project", null))
        assertTrue(watcher.observe("project", "New goal"))
        assertFalse(watcher.observe("project", "New goal"))
        assertTrue(watcher.observe("project", "Edited goal"))
        assertTrue(watcher.observe("project", null))
    }

    @Test fun localChangesBecomeTheBaseline() {
        val watcher = ProjectGoalWatcher()
        watcher.remember("project", "New local goal")

        assertFalse(watcher.observe("project", "New local goal"))
    }
}
