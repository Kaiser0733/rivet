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
        # MessageList is internal now so the scroll test can drive it with its own list state.
        messages = chat.split("internal fun MessageList", 1)[1].split(
            "private suspend fun scrollToConversationBottom", 1)[0]
        row = chat.split("internal fun MessageRow", 1)[1].split("private fun ReasoningRow", 1)[0]
        self.assertRegex(messages, r"SelectionContainer\s*\{\s*LazyColumn")
        for custom_action in ("combinedClickable", "Done selecting", "Copy or select text"):
            self.assertNotIn(custom_action, row)

    def test_compaction_publishes_start_and_scrolls_to_the_row(self):
        chat = (UI / "chat/ChatScreen.kt").read_text()
        model = (UI.parent / "chat/ChatViewModel.kt").read_text()
        self.assertIn(
            "state.notice, showBrowserCard, state.compacting, totalItems, state.acceptedMessageCount)", chat)
        follow = chat.split(
            "state.compacting, totalItems, state.acceptedMessageCount) {", 1)[1].split(
            "LaunchedEffect(listState)", 1)[0]
        # A reader who is only watching, not touching the list, still follows the new row.
        self.assertIn("if (followBottom && totalItems > 0 && !userScrolling && !listState.isScrollInProgress)", follow)
        self.assertIn("scrollToConversationBottom(listState, totalItems - 1)", follow)
        self.assertIn("onCompactionStarted = {", model)
        # Parallel runs moved the owner test to the registry; the compaction flag still
        # clears in finally for the run that owns the gap, not whichever is selected.
        self.assertIn("finally {\n                if (runRegistry.isActive(owner)) owner.update { state ->\n"
                      "                    if (state.compacting) state.copy(compacting = false) else state", model)

    def test_meter_and_compaction_use_the_built_request(self):
        model = (UI.parent / "chat/ChatViewModel.kt").read_text()
        prepare = (UI.parent / "chat/ContextPreparation.kt").read_text()
        self.assertNotIn("contextMeterTokens(", model)
        self.assertIn("contextReadingForRequest(config, request)", model)
        self.assertIn("ContextEstimate(footprint(messages)", prepare)
        self.assertIn("val historyTokens = context.footprint(compacted)", model)

    def test_termux_callback_targets_a_private_explicit_receiver(self):
        base = UI.parent
        bridge = (base / "runtime/TermuxBridge.kt").read_text()
        receipt = (base / "runtime/TermuxResultReceiver.kt").read_text()
        self.assertIn("Intent(app, TermuxResultReceiver::class.java).setAction(action)", bridge)
        self.assertLess(bridge.index("TermuxResultReceiver.register(action)"), bridge.index("app.startForegroundService(intent)"))
        self.assertIn("awaiting.remove(action)", receipt)
        self.assertIn("TermuxResultReceiver.unregister(action)", bridge)
        manifest = (base.parents[3] / "AndroidManifest.xml").read_text()
        self.assertRegex(manifest, r'android:name="\.runtime\.TermuxResultReceiver"\s+android:exported="false"\s*/>')

    def test_starter_opening_advertises_and_keeps_the_same_tool_calls(self):
        model = (UI.parent / "chat/ChatViewModel.kt").read_text()
        self.assertNotIn("if (starterOpening) emptyList() else BrowserTools", model)
        self.assertNotIn("response.copy(toolCalls = emptyList())", model)
        loop = (UI.parent / "agent/AgentLoop.kt").read_text()
        self.assertIn("dsml.finish(requestModel(", loop)

    def test_jump_action_uses_the_real_conversation_list(self):
        chat = (UI / "chat/ChatScreen.kt").read_text()
        self.assertIn("if (totalItems > 0 && listState.canScrollForward && (!followBottom || !nearBottom))", chat)
        self.assertIn('Text("Jump to latest"', chat)
        jump = chat.split("val jumpScope", 1)[1].split("Jump to latest", 1)[0]
        # Jumping re-attaches follow before it scrolls, so the rest of the reply stays in view.
        self.assertIn("userScrolling = false", jump)
        self.assertIn("followBottom = true", jump)
        self.assertIn("scrollToConversationBottom(listState, totalItems - 1)", jump)

    def test_landscape_shortcuts_leave_space_for_the_shared_recents(self):
        sidebar = (UI / "history/HistoryScreen.kt").read_text()
        self.assertIn("if (landscape) {", sidebar)
        self.assertIn("SidebarLink(label, icon, open, Modifier.weight(1f), compact = true)", sidebar)
        self.assertIn('item(key = "recents-heading")', sidebar)
        self.assertEqual(sidebar.count("relativeRecency(session.updatedAt)"), 1)

    def test_chat_only_shows_a_real_price_inside_recovery(self):
        chat = (UI / "chat/ChatScreen.kt").read_text()
        footer = chat.split("private fun ChangeSummary", 1)[1].split("private fun InterruptedTurnRow", 1)[0]
        self.assertNotIn("spendLabel", footer)
        self.assertIn('spendLabel?.takeUnless { it.startsWith("Unpriced model") }', chat)

    def test_archive_fts_finds_stored_text_within_one_session(self):
        import re
        import sqlite3

        store = (UI.parent / "storage/CodingSessions.kt").read_text()
        create = re.search(r'db.execSQL\("(CREATE VIRTUAL TABLE archive_fts [^"]+)"\)', store)
        self.assertIsNotNone(create)
        with sqlite3.connect(":memory:") as db:
            db.execute(create.group(1))
            rows = [
                ("chat", "You", 1, 1, 100, "The code word is monsoon-42 and the number is 5000."),
                ("chat", "Checkpoint", 2, 2, 200, "The earlier story was about a banyan tree."),
                ("chat", "You", 3, 3, 300, "What was the code word?"),
                ("other", "You", 4, 1, 400, "The code word is another value."),
            ]
            db.executemany("INSERT INTO archive_fts VALUES (?,?,?,?,?,?)", rows)
            self.assertEqual(db.execute(
                "SELECT body FROM archive_fts WHERE body MATCH ? AND session_id=? AND CAST(turn AS INTEGER)<=?",
                ('"code" "word"', "chat", 2),
            ).fetchall(), [(rows[0][-1],)])
            self.assertEqual(db.execute(
                "SELECT source FROM archive_fts WHERE body MATCH ? AND session_id=?",
                ('"banyan"', "chat"),
            ).fetchone(), ("Checkpoint",))
