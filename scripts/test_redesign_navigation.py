from pathlib import Path
import unittest


UI = Path(__file__).resolve().parents[1] / "app/src/main/java/com/kaiser/rivet/ui"


class RedesignNavigationTest(unittest.TestCase):
    def test_home_header_and_composer_are_the_only_main_entry_points(self):
        chat = (UI / "chat/ChatScreen.kt").read_text()
        header = chat.split("private fun ChatHeader(", 1)[1].split("@Composable", 1)[0]
        self.assertEqual(header.count("IconButton("), 2)
        self.assertIn("onClick = onOpenSidebar", header)
        self.assertIn("onClick = onNewChat", header)
        self.assertNotIn("clickable", header)
        self.assertIn("WorkingFolder(folderName, onOpenProjects)", chat)
        self.assertIn("selector?.invoke()", chat)
        self.assertNotIn("StatusFooter(", chat)
        self.assertNotIn("HistoryPane(", chat)
