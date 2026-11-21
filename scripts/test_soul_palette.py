from pathlib import Path
import re
import unittest


UI = Path(__file__).resolve().parents[1] / "app/src/main/java/com/kaiser/rivet/ui"


def contrast(first, second):
    def luminance(rgb):
        channels = [int(rgb[i:i+2], 16) / 255 for i in (0, 2, 4)]
        linear = [v / 12.92 if v <= 0.04045 else ((v + 0.055) / 1.055) ** 2.4 for v in channels]
        return sum(v * w for v, w in zip(linear, (0.2126, 0.7152, 0.0722)))

    low, high = sorted((luminance(first), luminance(second)))
    return (high + 0.05) / (low + 0.05)


class SoulPaletteTest(unittest.TestCase):
    def test_workbench_roles_and_readable_pairs_in_both_themes(self):
        theme = (UI / "Theme.kt").read_text()
        named = dict(re.findall(r"val (\w+) = Color\(0xFF([\dA-F]{6})\)", theme))
        roles = ("background", "surface", "surfaceContainerLow", "surfaceContainer",
                 "surfaceContainerHigh", "surfaceContainerHighest", "onSurface", "onSurfaceVariant",
                 "outlineVariant", "outline", "primary", "onPrimary", "errorContainer", "onErrorContainer")
        for mode, expected in {
            "Light": "FFFFFF F8FAFC F8FAFC F0F4F7 E8EEF3 E1E8EE 17212B 536271 DCE3E9 6C7A88 3567A7 FFFFFF F7CDD4 4A111A",
            "Dark": "15191F 1E252D 1E252D 252E38 2D3844 35424E F3F6F9 B8C3CE 39444F 8998A7 89B5F2 15191F 5A2734 FFD9DF",
        }.items():
            scheme = theme.split(f"val Rivet{mode}Colors = ")[1].split("\n)")[0]
            values = dict(re.findall(r"^    (\w+) = ([^,]+),", scheme, re.M))
            def rgb(role):
                value = values[role]
                return named[value] if value in named else value.removeprefix("Color(0xFF").removesuffix(")")
            with self.subTest(mode=mode):
                self.assertEqual([rgb(role) for role in roles], expected.split())
                self.assertEqual(rgb("onBackground"), rgb("onSurface"))
                self.assertEqual(rgb("tertiary"), rgb("onSurfaceVariant"))
                for surface in roles[:6]:
                    self.assertGreaterEqual(contrast(rgb("onSurface"), rgb(surface)), 4.5)
                    self.assertGreaterEqual(contrast(rgb("onSurfaceVariant"), rgb(surface)), 4.5)
                    self.assertGreaterEqual(contrast(rgb("outline"), rgb(surface)), 3)
                self.assertGreaterEqual(contrast(rgb("onPrimary"), rgb("primary")), 4.5)
                self.assertGreaterEqual(contrast(rgb("onErrorContainer"), rgb("errorContainer")), 4.5)

    def test_chat_uses_canvas_and_outlined_actions_use_the_interactive_edge(self):
        visuals = (UI / "RivetVisuals.kt").read_text()
        backdrop = visuals.split("fun RivetChatBackground")[1].split("@Composable")[0]
        self.assertIn(".background(MaterialTheme.colorScheme.background)", backdrop)
        self.assertIn("border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline", visuals)
