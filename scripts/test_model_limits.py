import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import generate_model_limits as gen


class GenerateModelLimitsTest(unittest.TestCase):
    def document(self):
        return {
            "openai": {
                "models": {
                    "gpt-4o": {
                        "modalities": {"output": ["text"]},
                        "limit": {"context": 128000, "output": 16384},
                    },
                    "image-only": {
                        "modalities": {"output": ["image"]},
                        "limit": {"context": 4096, "output": 1024},
                    },
                    "no-context": {
                        "modalities": {"output": ["text"]},
                        "limit": {"output": 1024},
                    },
                },
            },
            "small-route": {
                "models": {
                    "gpt-4o": {
                        "modalities": {"output": ["text"]},
                        "limit": {"context": 64000, "output": 8000},
                    },
                    "tiny": {
                        "modalities": {"output": ["text"]},
                        "limit": {"context": 8192},
                    },
                },
            },
        }

    def test_collect_keeps_text_models_and_minimum_canonical_limits(self):
        rows, ids = gen.collect(self.document())
        canonical = {(provider, model): (context, output) for provider, model, context, output in rows}
        self.assertEqual((64000, 8000), canonical[("*", "gpt-4o")])
        self.assertEqual((8192, None), canonical[("*", "tiny")])
        self.assertEqual((128000, 16384), canonical[("openai", "gpt-4o")])
        self.assertNotIn(("openai", "image-only"), canonical)
        self.assertNotIn(("openai", "no-context"), canonical)
        self.assertEqual(["gpt-4o", "tiny"], ids)

    def test_render_is_deterministic_and_names_its_source(self):
        rows, _ = gen.collect(self.document())
        text = gen.render(rows, "2026-11-25")
        self.assertIn("# generated: 2026-11-25", text)
        self.assertIn("# licence: MIT", text)
        self.assertEqual(text, gen.render(rows, "2026-11-25"))
        self.assertTrue(text.endswith("\n"))

    def test_positive_rejects_zero_and_bool(self):
        self.assertIsNone(gen.positive(0))
        self.assertIsNone(gen.positive(-1))
        self.assertIsNone(gen.positive(True))
        self.assertEqual(5, gen.positive(5))


if __name__ == "__main__":
    unittest.main()
