from pathlib import Path
import unittest


SOURCE = Path(__file__).resolve().parents[1] / "app/src/main/java/com/kaiser/rivet"


def source(path):
    return (SOURCE / path).read_text()


class ImprovementsTest(unittest.TestCase):
    def test_named_motion_uses_shared_timings_and_final_geometry(self):
        app = source("ui/RivetApp.kt")
        self.assertIn("slideTo(DrawerValue.Open)", app)
        self.assertIn("slideTo(DrawerValue.Closed)", app)
        self.assertIn("if (reducedMotion) snapTo(value)", app)
        self.assertIn("RivetMotion.DRAWER_MS", app)
        self.assertIn("RivetMotion.PAGE_MS", source("ui/provider/SettingsScreen.kt"))
        chat = source("ui/chat/ChatScreen.kt")
        self.assertIn("Modifier.panelEntry(progress)", chat)
        self.assertIn("RivetMotion.paneTravel.toPx() * (1f - progress)", source("ui/chat/FilePreview.kt"))
        self.assertIn("rivetMotionSpec(RivetMotion.DISCLOSURE_MS)", source("ui/UtilitySurfaces.kt"))
        motion = source("ui/EntryMotion.kt")
        self.assertIn("if (motionDisabled()) snap()", motion)
        self.assertIn("translationY = RivetMotion.panelRise.toPx() * (1f - progress)", motion)

    def test_chat_and_composer_share_the_measured_width_and_wide_pages_split(self):
        chat = source("ui/chat/ChatScreen.kt")
        self.assertIn("val lane = conversationWidth(maxWidth.value).dp", chat)
        lane = chat.split("Column(Modifier.width(lane)", 1)[1].split("private fun ChatHeader", 1)[0]
        self.assertIn("MessageList(visibleState", lane)
        self.assertIn("InputBar(streaming", lane)
        self.assertNotIn("widthIn(max = CHAT_CONTENT_MAX_WIDTH_DP", chat)
        self.assertIn("chatPaneFraction(availableWidth.value, divider)", source("ui/chat/FilePreview.kt"))
        self.assertIn("maxWidth >= RivetLayout.columnBreakpoint", source("ui/UtilitySurfaces.kt"))
        for name in ["provider/SettingsScreen", "workspace/ProjectsScreen", "browser/BrowserScreen", "local/ModelsScreen"]:
            self.assertIn("UtilityColumns(", source(f"ui/{name}.kt"))

    def test_completed_plain_assistant_text_uses_the_unspanned_renderer(self):
        chat = source("ui/chat/ChatScreen.kt").split("private fun InlineMarkdown", 1)[1]
        self.assertIn("if (annotated.spanStyles.isEmpty())", chat)
        self.assertIn("Text(annotated.text, modifier", chat)
        runs = source("ui/chat/AssistantInlineText.kt")
        self.assertIn("if (!run.code && !run.bold && !run.italic)", runs)
        self.assertIn("append(content)", runs)

    def test_hardware_input_reaches_the_composer_and_panel_keys(self):
        chat = source("ui/chat/ChatScreen.kt")
        self.assertIn("ComposerEnter.Send -> submit()", chat)
        self.assertIn("ComposerEnter.Newline ->", chat)
        self.assertIn("FLAG_SOFT_KEYBOARD", chat)
        self.assertIn("SelectionContainer { bubble() }", chat)
        self.assertNotIn("if (selectingText) SelectionContainer", chat)
        self.assertIn("dismissOnEscape(showCommands)", chat)
        self.assertIn("dismissOnEscape(canDismissPage)", source("ui/RivetApp.kt"))
        self.assertIn("dismissOnEscape(active != null", source("ui/chat/FilePreview.kt"))
        self.assertIn("collectIsHoveredAsState", source("ui/UtilitySurfaces.kt"))
        self.assertIn("collectIsFocusedAsState", source("ui/UtilitySurfaces.kt"))

    def test_sidebar_starts_with_the_real_mark_then_the_wordmark(self):
        sidebar = source("ui/history/HistoryScreen.kt")
        self.assertLess(sidebar.index("R.drawable.ic_launcher_foreground"), sidebar.index('Text("RIVET"'))
        self.assertLess(sidebar.index('Text("RIVET"'), sidebar.index('"Search chats"'))

    def test_empty_draft_is_reusable_and_absent_from_sidebar_recents(self):
        storage = source("storage/CodingSessions.kt")
        self.assertIn("reuseEmpty: Boolean = false", storage)
        self.assertIn("NOT EXISTS(SELECT 1 FROM events WHERE session_id=sessions.id)", storage)
        self.assertIn("copy(hasMessages = cursor.getInt(7) != 0)", storage)
        self.assertIn("reuseEmpty = true", source("chat/ChatViewModel.kt"))
        self.assertIn("session.hasMessages && session.title.contains", source("ui/history/HistoryScreen.kt"))

    def test_reasoning_signal_is_drawn_in_its_own_fixed_row_slot(self):
        picker = source("ui/chat/ModelSheet.kt")
        self.assertIn("selectionMotion == level && !instant", picker)
        self.assertIn("RivetMotion.effortSignalWidth", picker)
        self.assertIn(".clipToBounds(), content = signal", picker)
        signal = source("ui/chat/ReasoningSignal.kt")
        self.assertIn("repeat(count)", signal)
        self.assertNotIn("EffortPattern", signal)

    def test_compaction_process_and_saved_notice_both_reach_the_timeline(self):
        chat = source("ui/chat/ChatScreen.kt")
        self.assertIn('if (state.compacting) item(key = "manual-compaction") { CompactingRow() }', chat)
        self.assertIn("SystemNoticeRow(it.text)", chat)
        dots = source("ui/chat/CompactingRow.kt")
        self.assertIn("dots = dots % 3 + 1", dots)
        self.assertIn("RivetMotion.COMPACT_DOT_MS", dots)
        store = source("storage/CodingSessions.kt")
        self.assertIn("if (message.isTranscriptEvent())", store)
        self.assertIn("CompactionNotice.isNotice(validatedContext()?.kind)", source("agent/CompactionNotice.kt"))
        self.assertIn("!CompactionNotice.isNotice(it.kind)", source("agent/InternalContext.kt"))

    def test_context_meter_uses_the_active_config_and_updates_with_the_compaction_notice(self):
        chat = source("ui/chat/ChatScreen.kt")
        self.assertIn("state.contextMeter?.takeIf { it.matches(config) }", chat)
        self.assertIn("ConversationContextMeter(contextReading", chat)
        model = source("chat/ChatViewModel.kt")
        self.assertIn("contextEstimate = estimate, contextMeter = meter, error = null", model)
        self.assertIn("context.requestFor(noticed)", model)
        self.assertIn("currentEstimate - sampledEstimate", source("storage/ContextUsageDelta.kt"))
        self.assertIn("base_estimated_tokens", source("storage/CodingSessions.kt"))

    def test_models_use_one_grid_and_one_primary_action(self):
        models = source("ui/local/ModelsScreen.kt")
        self.assertIn("items(modelEntries, key = { it.id })", models)
        self.assertIn('RivetOutlinedButton(onClick = onPrimary) { Text(if (installed != null) "Use" else "Download") }', models)
        self.assertNotIn("AlternativeRow(", models)
        self.assertNotIn("items(ui.entries", models)
        self.assertIn(".distinctBy { it.id }", source("ui/local/ModelRows.kt"))
