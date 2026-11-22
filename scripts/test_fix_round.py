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
