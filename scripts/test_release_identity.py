import tempfile
import unittest
from pathlib import Path

from release_identity import verify_release_identity


class ReleaseIdentityTest(unittest.TestCase):
    def test_matching_pin_and_tag(self):
        with tempfile.TemporaryDirectory() as directory:
            pin = Path(directory, "production-signer.sha256")
            pin.write_text("AB" * 32 + "\n", encoding="ascii")
            verify_release_identity("0.8.1", "ab" * 32, pin, "v0.8.1")
            verify_release_identity("0.8.1", "ab" * 32, pin, "")

    def test_missing_or_invalid_pin_fails_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            pin = Path(directory, "production-signer.sha256")
            with self.assertRaisesRegex(AssertionError, "not pinned"):
                verify_release_identity("0.8.1", "ab" * 32, pin, "")
            pin.write_text("UNCONFIGURED", encoding="ascii")
            with self.assertRaisesRegex(AssertionError, "pin is invalid"):
                verify_release_identity("0.8.1", "ab" * 32, pin, "")

    def test_replacement_keystore_and_mismatched_tag_fail(self):
        with tempfile.TemporaryDirectory() as directory:
            pin = Path(directory, "production-signer.sha256")
            pin.write_text("ab" * 32, encoding="ascii")
            with self.assertRaisesRegex(AssertionError, "differs from pinned"):
                verify_release_identity("0.8.1", "cd" * 32, pin, "v0.8.1")
            with self.assertRaisesRegex(AssertionError, "does not match APK version"):
                verify_release_identity("0.8.1", "ab" * 32, pin, "v0.8.0")


if __name__ == "__main__":
    unittest.main()
