import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
UI = ROOT / "app/src/main/java/com/kaiser/rivet/ui"


class FixRoundSurfacesTest(unittest.TestCase):
    def test_wide_fold_contents_flow_in_their_own_lane(self):
        columns = (UI / "UtilitySurfaces.kt").read_text().split("internal fun UtilityColumns(", 1)[1]
        self.assertRegex(columns, r"Column\(Modifier.weight\(1f\)\) \{\s*leading\(\)\s*belowLeadingOnWide\?\.invoke\(this\)")
        phone = (UI / "provider/SettingsScreen.kt").read_text().split("private fun PhoneAccessDetail(", 1)[1]
        self.assertIn("belowLeadingOnWide = advanced", phone.split("private fun PhoneAddOnDetail", 1)[0])
        models = (UI / "local/ModelsScreen.kt").read_text()
        self.assertIn("LazyVerticalStaggeredGrid(", models)

    def test_chat_uses_default_selection_without_a_mode_switch(self):
        chat = (UI / "chat/ChatScreen.kt").read_text()
        messages = chat.split("private fun MessageList", 1)[1].split("private fun scrollToConversationBottom", 1)[0]
        row = chat.split("internal fun MessageRow", 1)[1].split("private fun ReasoningRow", 1)[0]
        self.assertRegex(messages, r"SelectionContainer\s*\{\s*LazyColumn")
        for custom_action in ("combinedClickable", "Done selecting", "Copy or select text"):
            self.assertNotIn(custom_action, row)
