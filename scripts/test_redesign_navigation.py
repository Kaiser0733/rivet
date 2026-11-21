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

    def test_sidebar_hosts_project_tools_browser_models_chats_and_settings(self):
        app = (UI / "RivetApp.kt").read_text()
        sidebar = (UI / "history/HistoryScreen.kt").read_text()
        for action in ("onOpenProjects", "onOpenTools", "onOpenBrowser", "onOpenModels", "onOpenSettings"):
            self.assertIn(action + " =", app.split("RivetSidebar(", 1)[1])
            self.assertIn(action, sidebar.split("fun RivetSidebar(", 1)[1])
        self.assertIn("session.title.contains(search.trim(), ignoreCase = true)", sidebar)
        self.assertIn("relativeRecency(session.updatedAt)", sidebar)
        self.assertIn('Text("New chat")', sidebar)

    def test_settings_is_a_full_screen_with_grouped_rows(self):
        app = (UI / "RivetApp.kt").read_text()
        settings = (UI / "provider/SettingsScreen.kt").read_text()
        self.assertNotIn("settingsSheetState", app)
        self.assertIn("RivetDestination.Settings ->", app)
        for name in ("Account & providers", "Phone access", "Skills", "Memory & compaction",
                     "Subagents", "Appearance", "Send feedback", "About"):
            self.assertIn(f'"{name}"', settings)
        self.assertIn("SettingsGroup {", settings)
        self.assertNotIn("ModalBottomSheet(", settings)

    def test_composer_slash_panel_lists_commands_and_uses_existing_actions(self):
        chat = (UI / "chat/ChatScreen.kt").read_text()
        app = (UI / "RivetApp.kt").read_text()
        panel = (UI / "chat/SlashMenu.kt").read_text()
        self.assertIn('SlashAction.Goal -> "/goal "', chat)
        self.assertIn("SlashAction.Feedback -> onSendFeedback()", chat)
        self.assertIn("SlashAction.Yolo -> onAutonomyChange(AutonomyMode.Yolo)", chat)
        self.assertIn("SlashAction.BasicYolo -> onAutonomyChange(AutonomyMode.BasicYolo)", chat)
        self.assertIn("onAutonomyChange = requestAutonomy", app)
        self.assertIn('group = "Skills"', panel)
        self.assertIn("skill.description", panel)

    def test_both_working_folder_controls_open_projects_and_keep_starters_there(self):
        app = (UI / "RivetApp.kt").read_text()
        self.assertIn("RivetDestination.Projects ->", app)
        self.assertGreaterEqual(app.count("onOpenProjects ="), 2)
        projects = (UI / "workspace/ProjectsScreen.kt").read_text()
        for phrase in ("Open another folder", "Recent folders", "Starters"):
            self.assertIn(f'"{phrase}"', projects)
        self.assertIn("Intent.ACTION_OPEN_DOCUMENT_TREE", projects)
        self.assertIn("viewModel.selectProject(uri, flags)", projects)
        self.assertIn("viewModel.installStarter(uri, flags, starter)", projects)
        chat = (UI / "chat/ChatScreen.kt").read_text()
        self.assertNotIn("ACTION_OPEN_DOCUMENT_TREE", chat)
        self.assertNotIn("StarterGallery(", chat)

    def test_tools_is_a_running_first_full_screen_and_terminal_returns_to_tools(self):
        app = (UI / "RivetApp.kt").read_text()
        self.assertNotIn("RightSlidePanel(", app)
        self.assertIn("RivetDestination.Tools ->", app)
        self.assertIn("RivetDestination.Terminal ->", app)
        self.assertIn("onClose = { destination = RivetDestination.Tools }", app)
        tools = (UI / "processes/ProcessesScreen.kt").read_text()
        self.assertIn("val (running, finished) = processItems.partition", tools)
        self.assertIn("if (showFinished) items(finished", tools)
        self.assertIn("mutableStateOf(false)", tools.split("private fun ProcessRow(", 1)[1])
        header = tools.split("commandFirstLine(process.label)", 1)[1].split("if (expanded)", 1)[0]
        self.assertIn("maxLines = 1", header)
        self.assertIn("TextOverflow.Ellipsis", header)

    def test_browser_uses_two_chrome_rows_and_opens_tab_actions_from_there(self):
        browser = (UI / "browser/BrowserScreen.kt").read_text()
        self.assertIn("onNewTab = session::newWindow", browser)
        self.assertIn("onClose(window.id)", browser)
        self.assertIn("window.favicon", browser)
        toolbar = browser.split("private fun BrowserToolbar(", 1)[1].split("@Composable", 1)[0]
        for value in ('"Page back"', '"Page forward"', '"Reload page"', '"Browser menu"', '"Share link"'):
            self.assertIn(value, toolbar)
        self.assertNotIn("BrowserWindowCard(", browser)
        self.assertNotIn("AlertDialog(", browser)

    def test_models_opens_as_a_page_and_model_links_use_an_in_page_form(self):
        app = (UI / "RivetApp.kt").read_text()
        self.assertNotIn("modelsSheetState", app)
        self.assertIn("RivetDestination.Models ->", app)
        models = (UI / "local/ModelsScreen.kt").read_text()
        self.assertIn('RivetPageHeader("Add a GGUF"', models)
        self.assertIn("viewModel.addByo(byoUrl)", models)

    def test_skill_install_uses_the_same_full_page_for_settings_and_shared_zips(self):
        app = (UI / "RivetApp.kt").read_text()
        self.assertIn("RivetDestination.SkillInstall ->", app)
        self.assertIn("initialZip = installZip", app)
        self.assertGreaterEqual(app.count("destination = RivetDestination.SkillInstall"), 2)
        install = (UI / "provider/SkillInstallDialog.kt").read_text()
        self.assertNotIn("Dialog(onDismissRequest", install)
        self.assertIn("RivetPageHeader(titleFor(step), onDismiss)", install)

    def test_deliverables_and_changed_files_open_the_workspace_preview(self):
        chat = (UI / "chat/ChatScreen.kt").read_text()
        preview = (UI / "chat/FilePreview.kt").read_text()
        model = (UI.parent / "chat/ChatViewModel.kt").read_text()
        self.assertIn("previewProjectFile", model)
        self.assertIn("WorkspaceFilePreview.read", model)
        self.assertIn("onOpenPreview = preview::open", chat)
        self.assertIn("presentedFilesForTurns", chat)
        self.assertIn("detectHorizontalDragGestures", preview)
        self.assertIn("Moved or deleted", preview)

    def test_turns_no_longer_offer_media_export_and_inbox_sharing_stays_available(self):
        chat = (UI / "chat/ChatScreen.kt").read_text()
        settings = (UI / "provider/SettingsScreen.kt").read_text()
        self.assertNotIn("Export ", chat)
        self.assertNotIn("watermark", settings.lower())
        paths = (UI.parents[4] / "res/xml/shared_file_paths.xml").read_text()
        import xml.etree.ElementTree as ET
        self.assertEqual([path.attrib["name"] for path in ET.fromstring(paths)], ["inbox"])
