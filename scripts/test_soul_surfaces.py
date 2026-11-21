from pathlib import Path
import unittest
import xml.etree.ElementTree as ET


MAIN = Path(__file__).resolve().parents[1] / "app/src/main"
UI = MAIN / "java/com/kaiser/rivet/ui"


class SoulSurfaceTest(unittest.TestCase):
    def test_tools_header_adds_one_independent_close_using_panel_dismissal(self):
        strings = {node.attrib["name"]: node.text for node in
                   ET.parse(MAIN / "res/values/strings.xml").getroot()}
        self.assertEqual(strings.get("open_tools"), "Tools")
        self.assertEqual(strings.get("close_tools"), "Close tools")
        chat = (UI / "chat/ChatScreen.kt").read_text()
        self.assertIn("ic_processes), stringResource(R.string.open_tools)", chat)
        shell = (UI / "RivetApp.kt").read_text()
        self.assertIn("onClose = closeProcesses", shell.split("RightSlidePanel(open = processesOpen")[1])
        self.assertIn("processesOpen -> closeProcesses()", shell)
        tools = (UI / "processes/ProcessesScreen.kt").read_text()
        self.assertEqual(tools.count("stringResource(R.string.close_tools)"), 1)
        self.assertIn("IconButton(onClick = onClose, modifier = Modifier.size(48.dp))", tools)
        self.assertIn("onClose = onClose", tools.split("ProcessesContent(processes, modifier, null")[1])

    def test_chat_setup_copy_follows_its_existing_state_and_action(self):
        chat = (UI / "chat/ChatScreen.kt").read_text()
        for old_state, mark, phrases in (
            ("if (providers.configs.isEmpty()) {\n                    EmptyState", "Provider",
             ("Choose a model for this chat.", "Open Settings", "Start offline")),
            ("} else if (projectBinding == ProjectBindingState.AccessLost && state.messages.isEmpty())", "Project",
             ("Rivet lost access to this folder.", "Select the same folder to continue this chat.", "Choose folder again")),
            ("} else if (state.projectName == null && state.messages.isEmpty() && !state.projectLoading)", "Project",
             ("Which folder do you want to work in?", "Choose a folder", "starterAction")),
            ("val ready = readyEntryPoints(projectBound", "Ready",
             ("Tell Rivet what you want to change.", "Your Agent settings control when Rivet asks before making changes.")),
        ):
            with self.subTest(state=mark, heading=phrases[0]):
                branch = chat.split(old_state, 1)[1].split("} else if", 1)[0]
                self.assertIn(f"RivetDoodle.{mark}", branch)
                for phrase in phrases:
                    self.assertIn(phrase, branch)

    def test_checkpoint_summary_appears_once_with_a_storage_kind(self):
        tools = (UI / "processes/ProcessesScreen.kt").read_text()
        row = tools.split("checkpoints.take(6).forEach", 1)[1].split("checkpointNotice?.let", 1)[0]
        self.assertEqual(row.count("checkpoint.summary"), 1)
        self.assertIn('if (checkpoint.gitRef != null) "Git checkpoint" else "File checkpoint"', row)
        self.assertIn("RestoreTarget(checkpoint, undoRun = false)", row)
        self.assertIn("Locale.getDefault()", row)
        self.assertNotIn("maxLines", row)

    def test_worker_sheet_selection_uses_the_selected_job_id(self):
        sheet = (UI / "chat/WorkerSheet.kt").read_text()
        self.assertIn("selected = entry.id == worker.id", sheet)
        self.assertIn("onSelect(entry.id)", sheet)
        self.assertIn("workerStateLabel(worker)", sheet)
        self.assertIn("Read-only transcript", sheet)

    def test_live_fold_keeps_the_public_preview_and_has_no_shimmer(self):
        chat = (UI / "chat/ChatScreen.kt").read_text()
        row = chat.split("private fun ReasoningRow(", 1)[1].split("@Composable\nprivate fun", 1)[0]
        self.assertIn("displaySafeText(thinkingPreview(text, streaming, scan))", row)
        self.assertIn('if (waitingWithoutPreview) "Waiting for a response" else "Think"', row)
        self.assertIn("colorScheme.onSurface", row)
        self.assertNotIn("rememberInfiniteTransition", chat)

    def test_outcome_uses_live_observation_and_manual_folds_honor_motion_scale(self):
        chat = (UI / "chat/ChatScreen.kt").read_text()
        turn = chat.split("private fun TurnBlockView(", 1)[1].split("@Composable\nprivate fun", 1)[0]
        self.assertIn("outcome.revealsAfter(observedOutcome)", turn)
        self.assertIn("SideEffect { observedOutcome = outcome }", turn)
        self.assertIn("when {", turn)
        motion = (UI / "FoldReveal.kt").read_text()
        self.assertIn("currentCoroutineContext()[MotionDurationScale]", motion)
        self.assertLess(motion.index("scale() == 0f"), motion.index("withFrameNanos { it }"))
        self.assertIn("EXPAND_MS = 180", motion)
        self.assertIn("COLLAPSE_MS = 150", motion)
