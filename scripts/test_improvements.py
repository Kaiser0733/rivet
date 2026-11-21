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
