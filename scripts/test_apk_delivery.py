"""Canonical APKs are verified before staging public/candidate filenames."""
import unittest
import tempfile
from stage_apk import stage_apk
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


class ApkDeliveryTest(unittest.TestCase):
    def test_staging_copies_exact_bytes_with_expected_names(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'app').mkdir()
            (root / 'app/build.gradle.kts').write_text('versionName = "0.11.0"\nversionCode = 22\n')
            for variant, name in [('debug', 'Rivet-0.11.0-code22-debug.apk'),
                                  ('release', 'Rivet-v0.11.0.apk')]:
                source = root / f'app/build/outputs/apk/{variant}/app-{variant}.apk'
                source.parent.mkdir(parents=True)
                source.write_bytes(b'verified APK fixture')
                staged = stage_apk(root, variant)
                self.assertEqual(name, staged.name)
                self.assertEqual(source.read_bytes(), staged.read_bytes())
                self.assertTrue(source.exists())

    def test_debug_staging_follows_verification(self):
        workflow = (ROOT / '.github/workflows/android-build.yml').read_text()
        self.assertIn('Stage verified debug APK', workflow)
        self.assertLess(workflow.index('Verify APK identity and signature'),
                        workflow.index('Stage verified debug APK'))
        self.assertIn('python3 scripts/stage_apk.py debug', workflow)
        self.assertNotIn('path: app/build/outputs/apk/debug/*.apk', workflow)
        self.assertIn('app/build/outputs/apk/debug/app-debug.apk', workflow)

    def test_release_staging_and_asset_contract(self):
        workflow = (ROOT / '.github/workflows/release.yml').read_text()
        self.assertIn('Stage verified release APK', workflow)
        self.assertLess(workflow.index('Verify APK identity and release signature'),
                        workflow.index('Stage verified release APK'))
        self.assertIn('python3 scripts/stage_apk.py release', workflow)
        public = workflow[workflow.index('Create draft GitHub Release'):]
        self.assertNotIn('app-release.apk', public)
        self.assertIn('STAGED_APK', public)
        self.assertIn('--draft', public)
        self.assertIn('release/production-signer.sha256', workflow)
        self.assertIn('if: always()', workflow)


if __name__ == '__main__':
    unittest.main()
