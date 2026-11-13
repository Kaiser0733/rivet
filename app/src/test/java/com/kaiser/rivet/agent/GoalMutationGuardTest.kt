package com.kaiser.rivet.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoalMutationGuardTest {
    @Test fun blocksDeletingRenamingOrMovingRivetDirectory() {
        assertTrue(GoalMutationGuard.isReservedMutation("delete_path", path = ".rivet"))
        assertTrue(GoalMutationGuard.isReservedMutation("delete_path", path = "./.rivet/"))
        assertTrue(GoalMutationGuard.isReservedMutation("rename_path", path = ".rivet", newName = "old"))
        assertTrue(GoalMutationGuard.isReservedMutation("move_path", path = ".rivet", destination = "tmp"))
        assertFalse(GoalMutationGuard.isReservedMutation("create_directory", path = ".rivet"))
        assertFalse(GoalMutationGuard.isReservedMutation("delete_path", path = ".rivet/skills/notes"))
    }

    @Test fun blocksRenameAndMoveDestinationsTargetingGoalOrRivet() {
        assertTrue(GoalMutationGuard.isReservedMutation("rename_path", path = ".rivet/draft.md", newName = "goal.md"))
        assertTrue(GoalMutationGuard.isReservedMutation("rename_path", path = "other", newName = ".rivet"))
        assertTrue(GoalMutationGuard.isReservedMutation("move_path", path = "tmp/goal.md", destination = ".rivet"))
        assertTrue(GoalMutationGuard.isReservedMutation("move_path", path = "tmp/.rivet", destination = ""))
        assertTrue(GoalMutationGuard.isReservedMutation("write_file", path = ".rivet/./goal.md"))
        assertFalse(GoalMutationGuard.isReservedMutation("write_file", path = ".rivet/skills/notes/SKILL.md"))
    }
}
