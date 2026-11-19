import unittest

from runtime_manifest import (
    ALLOWED_PERMISSIONS,
    verify_apk_runtime_manifest,
    verify_release_runtime_manifest,
)


DEBUG_TREE = """
E: manifest
  E: uses-permission
    A: android:name(0x01010003)="android.permission.INTERNET"
  E: application
    E: activity
      A: android:name(0x01010003)="com.kaiser.rivet.MainActivity"
    E: service
      A: android:name(0x01010003)="com.kaiser.rivet.runtime.PersistentProcessService"
      A: android:exported(0x01010010)=(type 0x12)0x00000000
      A: android:foregroundServiceType(0x010105e9)=(type 0x11)0x40000000
      E: property
        A: android:name(0x01010003)="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
        A: android:value(0x01010024)="User-visible loopback-only preview server for a selected project in the external browser."
    E: service
      A: android:name(0x01010003)="com.kaiser.rivet.local.ModelDownloadService"
      A: android:exported(0x01010010)=(type 0x12)0x00000000
      A: android:foregroundServiceType(0x010105e9)=(type 0x11)0x00000001
    E: provider
      A: android:name(0x01010003)="androidx.startup.InitializationProvider"
      A: android:exported(0x01010010)=(type 0x12)0x00000000
    E: receiver
      A: android:name(0x01010003)="androidx.profileinstaller.ProfileInstallReceiver"
      A: android:permission(0x01010006)="android.permission.DUMP"
      A: android:exported(0x01010010)=(type 0x12)0xffffffff
"""

RELEASE_MANIFEST = """<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <application>
        <activity android:name=".MainActivity" android:exported="true" />
        <service android:name=".runtime.PersistentProcessService" android:exported="false"
            android:foregroundServiceType="specialUse">
            <property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
                android:value="User-visible loopback-only preview server in Rivet." />
        </service>
        <service android:name=".local.ModelDownloadService" android:exported="false"
            android:foregroundServiceType="dataSync" />
        <provider android:name="androidx.startup.InitializationProvider" android:exported="false" />
        <receiver android:name="androidx.profileinstaller.ProfileInstallReceiver"
            android:permission="android.permission.DUMP" android:exported="true" />
    </application>
</manifest>"""

DEBUG_FILE_PROVIDER = """    E: provider
      A: android:name(0x01010003)="androidx.core.content.FileProvider" (Raw: "androidx.core.content.FileProvider")
      A: android:exported(0x01010010)=(type 0x12)0x00000000
      A: android:grantUriPermissions(0x0101001b)=(type 0x12)0xffffffff
      A: android:authorities(0x01010018)="com.kaiser.rivet.shared-files" (Raw: "com.kaiser.rivet.shared-files")
"""

DEBUG_FILE_PROVIDER_WITH_METADATA = """    E: provider
      A: android:name(0x01010003)="androidx.core.content.FileProvider" (Raw: "androidx.core.content.FileProvider")
      A: android:exported(0x01010010)=(type 0x12)0x00000000
      A: android:grantUriPermissions(0x0101001b)=(type 0x12)0xffffffff
      A: android:authorities(0x01010018)="com.kaiser.rivet.shared-files" (Raw: "com.kaiser.rivet.shared-files")
      E: meta-data
        A: android:name(0x01010003)="android.support.FILE_PROVIDER_PATHS" (Raw: "android.support.FILE_PROVIDER_PATHS")
        A: android:resource(0x01010025)=@xml/shared_file_paths (Raw: "@xml/shared_file_paths")
"""

RELEASE_STARTUP_PROVIDER = (
    '        <provider android:name="androidx.startup.InitializationProvider" android:exported="false" />'
)
RELEASE_FILE_PROVIDER = """        <provider android:name="androidx.core.content.FileProvider"
            android:exported="false" android:grantUriPermissions="true"
            android:authorities="com.kaiser.rivet.shared-files" />"""


def release_with_provider(provider: str) -> str:
    return RELEASE_MANIFEST.replace(
        RELEASE_STARTUP_PROVIDER, RELEASE_STARTUP_PROVIDER + "\n" + provider)


DEBUG_PHONE_CAPTURE_SERVICE = """    E: service
      A: android:name(0x01010003)="com.kaiser.rivet.ui.phone.PhoneCaptureService"
      A: android:exported(0x01010010)=(type 0x12)0x00000000
      A: android:foregroundServiceType(0x010105e9)=(type 0x11)0x00000020
"""

RELEASE_PHONE_CAPTURE_SERVICE = """        <service android:name=".ui.phone.PhoneCaptureService"
            android:exported="false" android:foregroundServiceType="mediaProjection" />"""

RELEASE_PHONE_PERMISSIONS = (
    '    <uses-permission android:name="com.termux.permission.RUN_COMMAND" />\n'
    '    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" />'
)


def release_with_capture(service: str) -> str:
    return RELEASE_MANIFEST.replace(
        RELEASE_STARTUP_PROVIDER, service + "\n" + RELEASE_STARTUP_PROVIDER)


def release_with_phone_permissions() -> str:
    return RELEASE_MANIFEST.replace(
        "<application>", RELEASE_PHONE_PERMISSIONS + "\n    <application>")


class RuntimeManifestTest(unittest.TestCase):
    def test_debug_apk_has_nonexported_special_use_process_service(self):
        verify_apk_runtime_manifest(DEBUG_TREE, ALLOWED_PERMISSIONS)

    def test_debug_apk_rejects_missing_or_unexpected_permission(self):
        with self.assertRaisesRegex(AssertionError, "lacks foreground service"):
            verify_apk_runtime_manifest(DEBUG_TREE, {"android.permission.INTERNET"})
        with self.assertRaisesRegex(AssertionError, "unexpected APK permissions"):
            verify_apk_runtime_manifest(DEBUG_TREE,
                ALLOWED_PERMISSIONS | {"android.permission.MANAGE_EXTERNAL_STORAGE"})

    def test_debug_apk_rejects_exported_or_wrong_type_service(self):
        with self.assertRaisesRegex(AssertionError, "non-exported"):
            verify_apk_runtime_manifest(DEBUG_TREE.replace("0x00000000", "0xffffffff"), ALLOWED_PERMISSIONS)
        with self.assertRaisesRegex(AssertionError, "specialUse"):
            verify_apk_runtime_manifest(DEBUG_TREE.replace("0x40000000", "0x00000003"), ALLOWED_PERMISSIONS)

    def test_debug_apk_accepts_unpadded_false_but_requires_explicit_visibility(self):
        verify_apk_runtime_manifest(DEBUG_TREE.replace("0x00000000", "0x0"), ALLOWED_PERMISSIONS)
        without_exported = DEBUG_TREE.replace(
            "      A: android:exported(0x01010010)=(type 0x12)0x00000000\n", "", 1)
        with self.assertRaisesRegex(AssertionError, "non-exported"):
            verify_apk_runtime_manifest(without_exported, ALLOWED_PERMISSIONS)
        with self.assertRaisesRegex(AssertionError, "non-exported"):
            verify_apk_runtime_manifest(DEBUG_TREE.replace("0x00000000", "0x1"), ALLOWED_PERMISSIONS)

    def test_debug_apk_requires_subtype_and_no_boot_receiver(self):
        with self.assertRaisesRegex(AssertionError, "subtype"):
            verify_apk_runtime_manifest(DEBUG_TREE.replace("E: property", "E: metadata"), ALLOWED_PERMISSIONS)
        with self.assertRaisesRegex(AssertionError, "boot"):
            verify_apk_runtime_manifest(DEBUG_TREE.replace("androidx.profileinstaller.ProfileInstallReceiver",
                "android.intent.action.BOOT_COMPLETED"), ALLOWED_PERMISSIONS)

    def test_debug_apk_rejects_unexpected_receiver_or_provider(self):
        extra_receiver = '    E: receiver\n      A: android:name(0x01010003)="example.UnexpectedReceiver"\n'
        with self.assertRaisesRegex(AssertionError, "only the existing AndroidX profile receiver"):
            verify_apk_runtime_manifest(DEBUG_TREE + extra_receiver,
                ALLOWED_PERMISSIONS)
        extra_provider = '    E: provider\n      A: android:name(0x01010003)="example.UnexpectedProvider"\n'
        with self.assertRaisesRegex(AssertionError, "only the existing AndroidX startup provider"):
            verify_apk_runtime_manifest(DEBUG_TREE + extra_provider,
                ALLOWED_PERMISSIONS)

    def test_release_merged_manifest_has_same_service_boundary(self):
        verify_release_runtime_manifest(RELEASE_MANIFEST)

    def test_release_merged_manifest_rejects_service_or_permission_regression(self):
        with self.assertRaisesRegex(AssertionError, "non-exported"):
            verify_release_runtime_manifest(RELEASE_MANIFEST.replace('android:exported="false"',
                'android:exported="true"'))
        with self.assertRaisesRegex(AssertionError, "specialUse"):
            verify_release_runtime_manifest(RELEASE_MANIFEST.replace('foregroundServiceType="specialUse"',
                'foregroundServiceType="dataSync"'))
        with self.assertRaisesRegex(AssertionError, "unexpected release permissions"):
            verify_release_runtime_manifest(RELEASE_MANIFEST.replace("<application>",
                '<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" /><application>'))

    def test_debug_apk_allows_locked_down_file_provider(self):
        verify_apk_runtime_manifest(DEBUG_TREE + DEBUG_FILE_PROVIDER, ALLOWED_PERMISSIONS)

    def test_debug_apk_allows_file_provider_with_nested_metadata(self):
        verify_apk_runtime_manifest(
            DEBUG_TREE + DEBUG_FILE_PROVIDER_WITH_METADATA, ALLOWED_PERMISSIONS)

    def test_debug_apk_rejects_file_provider_property_regressions(self):
        exported = DEBUG_FILE_PROVIDER_WITH_METADATA.replace(
            'A: android:exported(0x01010010)=(type 0x12)0x00000000',
            'A: android:exported(0x01010010)=(type 0x12)0xffffffff')
        with self.assertRaisesRegex(AssertionError, "non-exported"):
            verify_apk_runtime_manifest(DEBUG_TREE + exported, ALLOWED_PERMISSIONS)

        wrong_authority = DEBUG_FILE_PROVIDER_WITH_METADATA.replace(".shared-files", ".other-files")
        with self.assertRaisesRegex(AssertionError, "authorities"):
            verify_apk_runtime_manifest(DEBUG_TREE + wrong_authority, ALLOWED_PERMISSIONS)

        grant_false = DEBUG_FILE_PROVIDER_WITH_METADATA.replace(
            'A: android:grantUriPermissions(0x0101001b)=(type 0x12)0xffffffff',
            'A: android:grantUriPermissions(0x0101001b)=(type 0x12)0x00000000')
        with self.assertRaisesRegex(AssertionError, "grantUriPermissions"):
            verify_apk_runtime_manifest(DEBUG_TREE + grant_false, ALLOWED_PERMISSIONS)

        no_grant = DEBUG_FILE_PROVIDER_WITH_METADATA.replace(
            '      A: android:grantUriPermissions(0x0101001b)=(type 0x12)0xffffffff\n', "")
        with self.assertRaisesRegex(AssertionError, "grantUriPermissions"):
            verify_apk_runtime_manifest(DEBUG_TREE + no_grant, ALLOWED_PERMISSIONS)

    def test_release_merged_manifest_allows_locked_down_file_provider(self):
        verify_release_runtime_manifest(release_with_provider(RELEASE_FILE_PROVIDER))

    def test_release_merged_manifest_rejects_file_provider_property_regressions(self):
        exported = RELEASE_FILE_PROVIDER.replace('android:exported="false"', 'android:exported="true"')
        with self.assertRaisesRegex(AssertionError, "non-exported"):
            verify_release_runtime_manifest(release_with_provider(exported))

        wrong_authority = RELEASE_FILE_PROVIDER.replace(".shared-files", ".other-files")
        with self.assertRaisesRegex(AssertionError, "authorities"):
            verify_release_runtime_manifest(release_with_provider(wrong_authority))

        grant_false = RELEASE_FILE_PROVIDER.replace(
            'android:grantUriPermissions="true"', 'android:grantUriPermissions="false"')
        with self.assertRaisesRegex(AssertionError, "grantUriPermissions"):
            verify_release_runtime_manifest(release_with_provider(grant_false))

        no_grant = RELEASE_FILE_PROVIDER.replace(' android:grantUriPermissions="true"', "")
        with self.assertRaisesRegex(AssertionError, "grantUriPermissions"):
            verify_release_runtime_manifest(release_with_provider(no_grant))

    def test_debug_apk_allows_phone_projection_and_termux_permissions(self):
        self.assertIn("android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION", ALLOWED_PERMISSIONS)
        self.assertIn("com.termux.permission.RUN_COMMAND", ALLOWED_PERMISSIONS)
        verify_apk_runtime_manifest(DEBUG_TREE, ALLOWED_PERMISSIONS)

    def test_debug_apk_rejects_third_unexpected_permission(self):
        with self.assertRaisesRegex(AssertionError, "unexpected APK permissions"):
            verify_apk_runtime_manifest(DEBUG_TREE,
                ALLOWED_PERMISSIONS | {"android.permission.CAMERA"})

    def test_release_merged_manifest_allows_phone_permissions(self):
        verify_release_runtime_manifest(release_with_phone_permissions())

    def test_release_merged_manifest_rejects_third_unexpected_permission(self):
        extra = '<uses-permission android:name="android.permission.CAMERA" />'
        with self.assertRaisesRegex(AssertionError, "unexpected release permissions"):
            verify_release_runtime_manifest(release_with_phone_permissions().replace(
                "<application>", extra + "<application>"))

    def test_debug_apk_allows_locked_down_phone_capture_service(self):
        verify_apk_runtime_manifest(DEBUG_TREE + DEBUG_PHONE_CAPTURE_SERVICE, ALLOWED_PERMISSIONS)

    def test_debug_apk_rejects_phone_capture_service_regressions(self):
        exported = DEBUG_PHONE_CAPTURE_SERVICE.replace(
            "A: android:exported(0x01010010)=(type 0x12)0x00000000",
            "A: android:exported(0x01010010)=(type 0x12)0xffffffff")
        with self.assertRaisesRegex(AssertionError, "non-exported"):
            verify_apk_runtime_manifest(DEBUG_TREE + exported, ALLOWED_PERMISSIONS)

        wrong_type = DEBUG_PHONE_CAPTURE_SERVICE.replace("0x00000020", "0x00000021")
        with self.assertRaisesRegex(AssertionError, "mediaProjection"):
            verify_apk_runtime_manifest(DEBUG_TREE + wrong_type, ALLOWED_PERMISSIONS)

        wrong_name = DEBUG_PHONE_CAPTURE_SERVICE.replace(
            "com.kaiser.rivet.ui.phone.PhoneCaptureService", "example.UnexpectedCaptureService")
        with self.assertRaisesRegex(AssertionError, "phone-capture services"):
            verify_apk_runtime_manifest(DEBUG_TREE + wrong_name, ALLOWED_PERMISSIONS)

    def test_debug_apk_rejects_unexpected_third_service(self):
        extra = '    E: service\n      A: android:name(0x01010003)="example.UnexpectedService"\n'
        with self.assertRaisesRegex(AssertionError, "phone-capture services"):
            verify_apk_runtime_manifest(DEBUG_TREE + extra, ALLOWED_PERMISSIONS)

    def test_release_merged_manifest_allows_locked_down_phone_capture_service(self):
        verify_release_runtime_manifest(release_with_capture(RELEASE_PHONE_CAPTURE_SERVICE))

    def test_release_merged_manifest_allows_full_phone_surface(self):
        manifest = release_with_capture(RELEASE_PHONE_CAPTURE_SERVICE).replace(
            "<application>", RELEASE_PHONE_PERMISSIONS + "\n    <application>")
        verify_release_runtime_manifest(manifest)

    def test_release_merged_manifest_rejects_phone_capture_service_regressions(self):
        exported = RELEASE_PHONE_CAPTURE_SERVICE.replace(
            'android:exported="false"', 'android:exported="true"')
        with self.assertRaisesRegex(AssertionError, "non-exported"):
            verify_release_runtime_manifest(release_with_capture(exported))

        wrong_type = RELEASE_PHONE_CAPTURE_SERVICE.replace(
            'android:foregroundServiceType="mediaProjection"',
            'android:foregroundServiceType="dataSync"')
        with self.assertRaisesRegex(AssertionError, "mediaProjection"):
            verify_release_runtime_manifest(release_with_capture(wrong_type))

        wrong_name = RELEASE_PHONE_CAPTURE_SERVICE.replace(
            '.ui.phone.PhoneCaptureService', '.ui.phone.OtherCaptureService')
        with self.assertRaisesRegex(AssertionError, "phone-capture services"):
            verify_release_runtime_manifest(release_with_capture(wrong_name))


if __name__ == "__main__":
    unittest.main()
