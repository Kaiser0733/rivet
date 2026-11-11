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
    A: cleartextTrafficPermitted(0x010104ec)=(type 0x12)0xffffffff
"""


class NetworkSecurityTest(unittest.TestCase):
    def test_debug_apk_allows_cleartext_for_physical_mock_provider_testing(self):
        verify_debug_network_security(
            DEBUG_MANIFEST,
            DEBUG_NETWORK_CONFIG,
            {"res/xml/network_security_config.xml"},
            DEBUG_RESOURCES,
        )

    def test_debug_apk_rejects_other_network_policy_and_trust_overrides(self):
        for extra_policy in (
            "  E: domain-config (line=4)\n",
            "  E: debug-overrides (line=4)\n",
            "  E: trust-anchors (line=4)\n",
            "  E: pin-set (line=4)\n",
        ):
            with self.subTest(extra_policy=extra_policy.strip()), self.assertRaisesRegex(
                AssertionError, "only one base-config"
            ):
                verify_debug_network_security(
                    DEBUG_MANIFEST,
                    DEBUG_NETWORK_CONFIG + extra_policy,
                    {"res/xml/network_security_config.xml"},
                    DEBUG_RESOURCES,
                )

        with self.assertRaisesRegex(AssertionError, "allow cleartext"):
            verify_debug_network_security(
                DEBUG_MANIFEST,
                DEBUG_NETWORK_CONFIG.replace("0xffffffff", "0x00000000"),
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

        with self.assertRaisesRegex(AssertionError, "unrelated attributes"):
            verify_debug_network_security(
                DEBUG_MANIFEST,
                DEBUG_NETWORK_CONFIG +
                "    A: overridePins(0x0101053a)=(type 0x12)0xffffffff\n",
                {"res/xml/network_security_config.xml"},
                DEBUG_RESOURCES,
            )

    def test_release_requires_the_loopback_network_config(self):
        release_manifest = """<manifest xmlns:android="http://schemas.android.com/apk/res/android">
            <application android:networkSecurityConfig="@xml/network_security_config" />
        </manifest>"""
        config = """<network-security-config>
            <base-config cleartextTrafficPermitted="false" />
            <domain-config cleartextTrafficPermitted="true">
                <domain>127.0.0.1</domain><domain>localhost</domain><domain>::1</domain>
            </domain-config>
        </network-security-config>"""
        verify_release_network_exclusion(
            release_manifest, {"res/xml/network_security_config.xml"}, config
        )

    def test_release_rejects_missing_or_widened_network_config(self):
        release_manifest = """<manifest xmlns:android="http://schemas.android.com/apk/res/android">
            <application android:networkSecurityConfig="@xml/network_security_config" />
        </manifest>"""
        with self.assertRaisesRegex(AssertionError, "release manifest"):
            verify_release_network_exclusion(
                """<manifest xmlns:android="http://schemas.android.com/apk/res/android">
                    <application />
                </manifest>""",
                {"res/xml/network_security_config.xml"}, None
            )

        with self.assertRaisesRegex(AssertionError, "release resources"):
            verify_release_network_exclusion(release_manifest, set(), None)

        cleartext_manifest = """<manifest xmlns:android="http://schemas.android.com/apk/res/android">
            <application android:networkSecurityConfig="@xml/network_security_config"
                android:usesCleartextTraffic="true" />
        </manifest>"""
        with self.assertRaisesRegex(AssertionError, "global cleartext"):
            verify_release_network_exclusion(cleartext_manifest, set(), None)

        bad_config = """<network-security-config>
            <base-config cleartextTrafficPermitted="true" />
        </network-security-config>"""
        with self.assertRaisesRegex(AssertionError, "base-config"):
            verify_release_network_exclusion(
                release_manifest, {"res/xml/network_security_config.xml"}, bad_config
            )


if __name__ == "__main__":
    unittest.main()
