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

    def test_compaction_publishes_start_and_scrolls_to_the_row(self):
        chat = (UI / "chat/ChatScreen.kt").read_text()
        model = (UI.parent / "chat/ChatViewModel.kt").read_text()
        self.assertIn("state.notice, showBrowserCard, state.compacting)", chat)
        self.assertIn("onCompactionStarted = {", model)
        self.assertIn("finally {\n                        if (ticket == generation) _uiState.update { it.copy(compacting = false) }", model)

    def test_meter_and_compaction_use_the_built_request(self):
        model = (UI.parent / "chat/ChatViewModel.kt").read_text()
        prepare = (UI.parent / "chat/ContextPreparation.kt").read_text()
        self.assertNotIn("contextMeterTokens(", model)
        self.assertIn("contextReadingForRequest(config, request)", model)
        self.assertIn("ContextEstimate(footprint(messages)", prepare)
        self.assertIn("val historyTokens = context.footprint(compacted)", model)

    def test_termux_callback_targets_a_private_receiver(self):
        base = UI.parent
        bridge = (base / "runtime/TermuxBridge.kt").read_text()
        self.assertIn("Intent(app, TermuxResultReceiver::class.java)", bridge)
        manifest = (base.parents[3] / "AndroidManifest.xml").read_text()
        self.assertRegex(manifest, r'android:name="\.runtime\.TermuxResultReceiver"\s+android:exported="false"')
        self.assertNotIn("app.registerReceiver(", bridge)

    def test_starter_opening_advertises_and_keeps_the_same_tool_calls(self):
        model = (UI.parent / "chat/ChatViewModel.kt").read_text()
        self.assertNotIn("if (starterOpening) emptyList() else BrowserTools", model)
        self.assertNotIn("response.copy(toolCalls = emptyList())", model)
        loop = (UI.parent / "agent/AgentLoop.kt").read_text()
        self.assertIn("dsml.finish(requestModel(", loop)
