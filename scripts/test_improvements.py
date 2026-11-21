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
