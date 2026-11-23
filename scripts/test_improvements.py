from pathlib import Path
import struct
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
        self.assertIn("if (path == null && !showCode && !run.bold && !run.italic)", runs)
        self.assertIn("append(content)", runs)

    def test_hardware_input_reaches_the_composer_and_panel_keys(self):
        chat = source("ui/chat/ChatScreen.kt")
        self.assertIn("ComposerEnter.Send -> submit()", chat)
        self.assertIn("ComposerEnter.Newline ->", chat)
        self.assertIn("FLAG_SOFT_KEYBOARD", chat)
        self.assertIn("SelectionContainer {", chat)
        self.assertNotIn("if (selectingText) SelectionContainer", chat)
        self.assertIn("dismissOnEscape(showCommands)", chat)
        self.assertIn("dismissOnEscape(canDismissPage)", source("ui/RivetApp.kt"))
        self.assertIn("dismissOnEscape(active != null", source("ui/chat/FilePreview.kt"))
        self.assertIn("collectIsHoveredAsState", source("ui/UtilitySurfaces.kt"))
        self.assertIn("collectIsFocusedAsState", source("ui/UtilitySurfaces.kt"))

    def test_sidebar_starts_with_the_real_mark_then_the_wordmark(self):
        sidebar = source("ui/history/HistoryScreen.kt")
        self.assertLess(sidebar.index("R.drawable.sidebar_rivet_mark"), sidebar.index('Text("RIVET"'))
        self.assertLess(sidebar.index('Text("RIVET"'), sidebar.index('"Search chats"'))

    def test_sidebar_mark_is_prepared_for_its_display_densities(self):
        sidebar = source("ui/history/HistoryScreen.kt")
        self.assertIn("Modifier.size(32.dp).clip(CircleShape)", sidebar)
        resources = SOURCE.parents[3] / "res"
        for density, pixels in [("ldpi", 24), ("mdpi", 32), ("tvdpi", 43), ("hdpi", 48),
                                ("xhdpi", 64), ("xxhdpi", 96), ("xxxhdpi", 128)]:
            with self.subTest(density=density):
                mark = resources / f"drawable-{density}" / "sidebar_rivet_mark.png"
                self.assertTrue(mark.is_file(), mark)
                self.assertEqual((pixels, pixels), struct.unpack(">II", mark.read_bytes()[16:24]))
        self.assertFalse((resources / "drawable-nodpi/sidebar_rivet_mark.png").exists())

    def test_attachment_sheet_dispatches_only_the_three_icon_rows(self):
        chat = source("ui/chat/ChatScreen.kt")
        self.assertNotIn("Include clipboard text", chat)
        self.assertNotIn("OnPrimaryClipChangedListener", chat)
        self.assertIn("rememberComposerPickers(sessionId, onStageAttachments, onPickerUnavailable)", chat)
        # Each row hands over the whole selection, not one URI.
        pickers = source("ui/chat/ComposerPickers.kt")
        self.assertIn("onPick: (String?, List<Uri>, Boolean, File?) -> Unit", pickers)
        self.assertIn("ActivityResultContracts.PickMultipleVisualMedia()", pickers)
        self.assertIn("ActivityResultContracts.OpenMultipleDocuments()", pickers)
        self.assertIn("AttachmentSheet(", chat)
        sheet = source("ui/chat/AttachmentSheet.kt")
        self.assertIn("Column(", sheet)
        self.assertIn("ComposerPicker.entries.forEach", sheet)
        for picker, icon in [("Camera", "camera"), ("Photos", "photo"), ("Files", "attachment")]:
            self.assertIn(f"ComposerPicker.{picker} -> R.drawable.ic_{icon}", sheet)
        self.assertIn("CircleShape", sheet)
        self.assertIn("onPick(picker)", sheet)

    def test_browser_card_follows_page_open_even_without_a_renderer(self):
        chat = source("ui/chat/ChatScreen.kt")
        self.assertIn("browserState.chatPreviewVisible(state.currentSessionId)", chat)
        tools = source("browser/BrowserTools.kt")
        self.assertIn("session.openForAgent(normalized", tools)
        session = source("browser/BrowserSession.kt")
        opened = session.split("suspend fun openForAgent(", 1)[1].split("suspend fun open(", 1)[0]
        self.assertLess(opened.index("markChatBrowserWork(page)"), opened.index("openText("))
        self.assertIn("chatPage = BrowserChatPage(result.url, result.title, excerpt)", opened)
        self.assertIn("BrowserGovernor.canTakeScreenshot(result.tier)", opened)
        card = source("ui/chat/LiveBrowserCard.kt")
        self.assertIn("ContentScale.Crop", card)
        self.assertIn('Text("Open browser"', card)

    def test_only_assistant_inline_images_use_the_bounded_tappable_renderer(self):
        chat = source("ui/chat/ChatScreen.kt")
        markdown = chat.split("internal fun AssistantMarkdown(", 1)[1].split("private fun InlineMarkdown(", 1)[0]
        self.assertIn("inline = true", markdown)
        self.assertIn("InlineChatImage(bitmap", markdown)
        app_image = chat.split("private fun BrowserImage(", 1)[1].split("private fun MarkdownTableCell(", 1)[0]
        self.assertIn("else if (inline)", app_image)
        self.assertIn("InlineChatImage(bitmap", app_image)
        self.assertIn("LocalOpenChatImage.current", app_image)
        self.assertIn("LocalOpenChatImage provides openChatImage", chat)
        image = source("ui/chat/InlineChatImage.kt")
        self.assertIn("inlineMessageImageSize(", image)
        self.assertIn("ContentScale.Fit", image)
        self.assertIn('onClickLabel = "Open image preview"', image)

    def test_empty_draft_is_reusable_and_absent_from_sidebar_recents(self):
        storage = source("storage/CodingSessions.kt")
        self.assertIn("reuseEmpty: Boolean = false", storage)
        self.assertIn("NOT EXISTS(SELECT 1 FROM events WHERE session_id=sessions.id)", storage)
        self.assertIn("copy(hasMessages = cursor.getInt(7) != 0)", storage)
        self.assertIn("reuseEmpty = _uiState.value.goal == null", source("chat/ChatViewModel.kt"))
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

    def test_settings_values_wrap_in_the_weighted_column(self):
        settings = source("ui/provider/SettingsScreen.kt")
        rows = settings.split("private fun SectionListItem(", 1)[1].split("private fun MemoryDetail", 1)[0]
        self.assertIn("UtilityRow(iconRes, title, summary", rows)
        row = source("ui/UtilitySurfaces.kt").split("internal fun UtilityRow(", 1)[1].split("internal fun UtilityLead(", 1)[0]
        self.assertLess(row.index("Column(Modifier.weight(1f))"), row.index("Text(value"))
        value = row.split("Text(value", 1)[1].split("Icon(painterResource(trailingIcon)", 1)[0]
        self.assertNotIn("maxLines", value)
        self.assertNotIn("Ellipsis", value)
        provider_rows = settings.split("private fun ProviderRow(", 1)[1]
        self.assertNotIn("TextOverflow.Ellipsis", provider_rows)
        picker = source("ui/chat/ModelSheet.kt").split("private fun PopoverRow(", 1)[1].split("private fun PaneHeader(", 1)[0]
        self.assertIn("Column(Modifier.weight(1f)", picker)
        self.assertNotIn("TextOverflow.Ellipsis", picker)

    def test_about_destinations_use_the_same_settings_rows(self):
        about = source("ui/provider/SettingsScreen.kt").split("private fun AboutDetail", 1)[1]
        self.assertIn('SectionListItem(R.drawable.ic_history, "Updates", "", onOpenUpdates', about)
        self.assertIn('SectionListItem(R.drawable.ic_models, "Conversation usage", "", onShowUsage', about)
        self.assertNotIn("UsageRow(", about)

    def test_every_sidebar_timestamp_uses_the_same_relative_format(self):
        sidebar = source("ui/history/HistoryScreen.kt")
        self.assertNotIn("DateUtils", sidebar)
        self.assertIn("relativeRecency(session.updatedAt)", sidebar)
        self.assertIn("R.plurals.sidebar_days_ago", sidebar)

    def test_public_support_uses_the_existing_coffee_action(self):
        support = source("ui/provider/SupportSection.kt")
        self.assertNotIn("UPI_ID", support)
        self.assertNotIn("ClipboardManager", support)
        self.assertIn("R.string.support_coffee", support)
        self.assertIn("openSupport(context)", support)

    def test_deliverables_are_a_default_in_both_prompts(self):
        rule = source("agent/PresentedFile.kt")
        self.assertIn("Before your final reply, use present for finished files the user asked you to deliver", rule)
        self.assertIn("Leave out scratch files, build artifacts and incidental project edits.", rule)
        self.assertIn("Show 1–4 requested deliverables", source("agent/AgentToolExecutor.kt"))
        model = source("chat/ChatViewModel.kt")
        self.assertIn("if (presentAvailable)", model)
        self.assertIn("phoneAccess = false, presentAvailable = false", model)
        self.assertIn("append(PRESENT_DELIVERABLES_RULE)", source("local/LocalToolProtocol.kt"))

    def test_only_bound_project_files_are_linked_to_the_existing_preview(self):
        chat = source("ui/chat/ChatScreen.kt")
        self.assertIn("LocalAssistantFileLinks provides linkDoor", chat)
        self.assertIn("if (exists && chatViewModel.uiState.value.currentSessionId == session)", chat)
        self.assertIn("preview.open(path)", chat)
        text = source("ui/chat/AssistantInlineText.kt")
        self.assertIn("it in existingFiles", text)
        self.assertIn("LinkAnnotation.Clickable", text)
        self.assertIn("WorkspaceFilePreview.isFile", source("chat/ChatViewModel.kt"))
        workspace = source("workspace/WorkspaceFilePreview.kt")
        self.assertIn("workspace.stat(path).takeUnless { it.directory }", workspace)
        self.assertIn("val entry = statFile(workspace, path)", workspace)
