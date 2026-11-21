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
