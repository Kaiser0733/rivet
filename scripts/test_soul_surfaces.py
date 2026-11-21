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
