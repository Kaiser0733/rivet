from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/java/com/kaiser/rivet"
TEST = ROOT / "app/src/test/java/com/kaiser/rivet"


class ManualCompactTest(unittest.TestCase):
    def test_compact_row_is_enabled_with_the_dsh_description(self):
        panel = (SRC / "ui/chat/SlashMenu.kt").read_text()
        self.assertIn('row("Commands", "Compact", "Compact older conversation history", SlashAction.Compact)', panel)
        self.assertNotIn("action != SlashAction.Compact &&", panel)
        self.assertIn("compactEnabled: Boolean = true", panel)
        sheet = (SRC / "ui/chat/SkillCommandSheet.kt").read_text()
        self.assertNotIn("Runs automatically", sheet)
        self.assertIn('row.action == SlashAction.Compact && !row.enabled -> "Wait for the reply to finish"', sheet)

    def test_the_tap_fires_the_view_model_compaction(self):
        chat = (SRC / "ui/chat/ChatScreen.kt").read_text()
        self.assertIn('onSend("/${it.token}")', chat)
        model = (SRC / "chat/ChatViewModel.kt").read_text()
        self.assertIn("BuiltInSlash.Compact -> compactHistory()", model)
        self.assertIn("compactEnabled = compactRowEnabled(streaming)", chat)

    def test_the_trigger_decision_is_a_pure_seam(self):
        seam = (SRC / "chat/ManualCompaction.kt").read_text()
        self.assertIn("internal enum class CompactTrigger { Fire, RunActive, NothingToCompact }", seam)
        self.assertIn("internal fun compactTrigger(streaming: Boolean, hasMessages: Boolean): CompactTrigger", seam)
        self.assertIn("internal fun compactRowEnabled(streaming: Boolean): Boolean", seam)
        test = (TEST / "chat/ManualCompactionTest.kt").read_text()
        self.assertIn("CompactTrigger.RunActive", test)
        self.assertIn("CompactTrigger.NothingToCompact", test)
        self.assertIn("CompactTrigger.Fire", test)

    def test_manual_compaction_reuses_the_runs_preparation_and_notice(self):
        model = (SRC / "chat/ChatViewModel.kt").read_text()
        body = model.split("fun compactHistory()", 1)[1].split("/** Move a no-project goal", 1)[0]
        self.assertIn("ContextPreparation(sql, sessionId", body)
        self.assertIn('context.prepare(owner.activeMessages, force = true, reason = "manual")', body)
        self.assertIn("savedGoal = { owner.value.goal.ownedBy(sessionId)?.text }", body)
        self.assertIn("val visible = sql.recent(sessionId)", body)
        self.assertIn("activeSummary = context.summary", body)
        store = (SRC / "storage/CodingSessions.kt").read_text()
        self.assertIn("insertTranscriptNotice(db, id, InternalContext.compactionNotice(", store)
        # The automatic path uses the same preparation.
        self.assertIn("context.prepare(candidate, force)", model)
        self.assertNotIn("TaskCheckpoint(", body)

    def test_edge_cases_get_plain_outcomes_and_the_existing_error_copy(self):
        model = (SRC / "chat/ChatViewModel.kt").read_text()
        body = model.split("fun compactHistory()", 1)[1].split("/** Move a no-project goal", 1)[0]
        self.assertIn('"Cannot compact while a reply is running."', body)
        self.assertIn('"Nothing to compact yet."', body)
        self.assertIn('"Rivet could not shorten this session\'s context. The conversation was kept."', body)
        self.assertIn("if (compacted == owner.activeMessages)", body)

    def test_the_view_model_behaviour_has_jit_tests(self):
        test = (TEST / "chat/ChatViewModelTest.kt").read_text()
        for name in (
            "manualCompactionSummarisesTheIdleSessionAndLeavesTheNoticeRow",
            "manualCompactionOnAFreshSessionIsAPlainNoOp",
            "manualCompactionWithNothingWorthCompactingKeepsTheSession",
            "manualCompactionWaitsWhileAReplyIsRunning",
            "failedManualCompactionKeepsTheSessionUntouched",
            "cancellingManualCompactionKeepsTheDurableSession",
        ):
            self.assertIn(f"fun {name}()", test)
        rows = (TEST / "ui/chat/SkillCommandSheetTest.kt").read_text()
        self.assertIn("assertTrue(compact.enabled)", rows)
        self.assertIn("fun compactWaitsWhileAReplyRunsAndTheOtherRowsStayAvailable()", rows)


if __name__ == "__main__":
    unittest.main()
