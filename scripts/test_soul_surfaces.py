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
        shell = (UI / "RivetApp.kt").read_text()
        self.assertIn("onClose = closeProcesses", shell.split("RightSlidePanel(open = processesOpen")[1])
        self.assertIn("processesOpen -> closeProcesses()", shell)
        tools = (UI / "processes/ProcessesScreen.kt").read_text()
        self.assertEqual(tools.count("stringResource(R.string.close_tools)"), 1)
        self.assertIn("IconButton(onClick = onClose, modifier = Modifier.size(48.dp))", tools)
        self.assertIn("onClose = onClose", tools.split("ProcessesContent(processes, modifier, null")[1])

    def test_chat_setup_copy_still_distinguishes_missing_models_and_folders(self):
        chat = (UI / "chat/ChatScreen.kt").read_text()
        self.assertIn('providers.configs.isEmpty() -> "Connect a model in Settings to start."', chat)
        self.assertIn('state.projectName == null && !state.streaming -> "Choose a working folder to start."', chat)
        self.assertIn('projectBinding == ProjectBindingState.AccessLost -> PROJECT_ACCESS_LOST_MESSAGE', chat)

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

    def test_specialist_copy_names_the_existing_action_or_value(self):
        cases = (
            ("provider/SettingsScreen.kt", 'if (active == null)', "Choose the service you want to connect."),
            ("local/ModelsScreen.kt", 'if (totalRamMb > 0) {\n                Text(', "Estimated model size limit: about ${formatParams(ceiling.maxParamsB)} at ${ceiling.contextMax / 1024}K context."),
            ("provider/SettingsScreen.kt", 'private fun PhoneAccessDetail()', 'SectionHeading("Share to Rivet")'),
            ("provider/SettingsScreen.kt", 'SectionHeading("Screen capture")', "Rivet asks before each capture. After you allow it, tap Capture now in the notification."),
            ("starter/StarterGallery.kt", 'Text("Starters"', "Choose a starter and an empty folder for its files."),
            ("browser/BrowserScreen.kt", 'Text(', "Enter a web address, then tap Go."),
            ("chat/ReplayExportSheet.kt", 'busy = true', 'feedback = "Creating your replay…"'),
        )
        for path, anchor, expected in cases:
            with self.subTest(path=path, copy=expected):
                self.assertIn(expected, (UI / path).read_text().split(anchor, 1)[1])

    def test_zero_motion_uses_final_values_during_composition(self):
        motion = (UI / "FoldReveal.kt").read_text()
        self.assertIn("if (instant) expanded else composed", motion)
        self.assertIn("if (instant) target else progress", motion)
        self.assertIn("return if (instant) target else rotation", motion)
        self.assertIn("return if (instant) 1f else progress", motion)
