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
