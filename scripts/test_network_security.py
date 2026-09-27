import unittest

from verify_network_security import (
    verify_debug_network_security,
    verify_release_network_exclusion,
)


DEBUG_MANIFEST = """
E: application
  A: android:networkSecurityConfig(0x01010527)=@0x7f0f0000
"""

DEBUG_RESOURCES = """
resource 0x7f0f0000 com.kaiser.rivet:xml/network_security_config:
"""

DEBUG_NETWORK_CONFIG = """
E: network-security-config (line=2)
  E: base-config (line=3)
    A: cleartextTrafficPermitted(0x010104ec)=(type 0x12)0x00000000
  E: domain-config (line=4)
    A: cleartextTrafficPermitted(0x010104ec)=(type 0x12)0xffffffff
    E: domain (line=5)
      C: "localhost"
    E: domain (line=6)
      C: "127.0.0.1"
"""


class NetworkSecurityTest(unittest.TestCase):
    def test_debug_apk_allows_only_the_two_loopback_hosts(self):
        verify_debug_network_security(
            DEBUG_MANIFEST,
            DEBUG_NETWORK_CONFIG,
            {"res/xml/network_security_config.xml"},
            DEBUG_RESOURCES,
        )

    def test_debug_apk_rejects_global_cleartext_and_extra_domains(self):
        globally_cleartext = DEBUG_NETWORK_CONFIG.replace(
            "0x00000000", "0xffffffff", 1
        )
        with self.assertRaisesRegex(AssertionError, "base-config"):
            verify_debug_network_security(
                DEBUG_MANIFEST,
                globally_cleartext,
                {"res/xml/network_security_config.xml"},
                DEBUG_RESOURCES,
            )

        extra_host = DEBUG_NETWORK_CONFIG.replace(
            "127.0.0.1", "0.0.0.0"
        )
        with self.assertRaisesRegex(AssertionError, "domain"):
            verify_debug_network_security(
                DEBUG_MANIFEST,
                extra_host,
                {"res/xml/network_security_config.xml"},
                DEBUG_RESOURCES,
            )

        with self.assertRaisesRegex(AssertionError, "global cleartext"):
            verify_debug_network_security(
                DEBUG_MANIFEST + "  A: android:usesCleartextTraffic(0x010104ec)=(type 0x12)0xffffffff\n",
                DEBUG_NETWORK_CONFIG,
                {"res/xml/network_security_config.xml"},
                DEBUG_RESOURCES,
            )

        subdomains = DEBUG_NETWORK_CONFIG.replace(
            'C: "localhost"',
            'A: includeSubdomains(0x010104ed)=(type 0x12)0xffffffff\n'
            '      C: "localhost"',
        )
        with self.assertRaisesRegex(AssertionError, "subdomains"):
            verify_debug_network_security(
                DEBUG_MANIFEST,
                subdomains,
                {"res/xml/network_security_config.xml"},
                DEBUG_RESOURCES,
            )

    def test_release_outputs_exclude_debug_network_configuration(self):
        release_manifest = """<manifest xmlns:android="http://schemas.android.com/apk/res/android">
            <application />
        </manifest>"""
        verify_release_network_exclusion(release_manifest, set())

    def test_release_manifest_or_resources_cannot_reference_debug_config(self):
        release_manifest = """<manifest xmlns:android="http://schemas.android.com/apk/res/android">
            <application android:networkSecurityConfig="@xml/network_security_config" />
        </manifest>"""
        with self.assertRaisesRegex(AssertionError, "release manifest"):
            verify_release_network_exclusion(
                release_manifest, {"res/xml/network_security_config.xml"}
            )

        clean_manifest = """<manifest xmlns:android="http://schemas.android.com/apk/res/android">
            <application />
        </manifest>"""
        with self.assertRaisesRegex(AssertionError, "release resources"):
            verify_release_network_exclusion(
                clean_manifest, {"res/xml/network_security_config.xml"}
            )

        cleartext_manifest = """<manifest xmlns:android="http://schemas.android.com/apk/res/android">
            <application android:usesCleartextTraffic="true" />
        </manifest>"""
        with self.assertRaisesRegex(AssertionError, "global cleartext"):
            verify_release_network_exclusion(cleartext_manifest, set())


if __name__ == "__main__":
    unittest.main()
