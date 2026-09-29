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
    <application>
        <activity android:name=".MainActivity" android:exported="true" />
        <service android:name=".runtime.PersistentProcessService" android:exported="false"
            android:foregroundServiceType="specialUse">
            <property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
                android:value="User-visible loopback-only preview server in Rivet." />
        </service>
        <provider android:name="androidx.startup.InitializationProvider" android:exported="false" />
        <receiver android:name="androidx.profileinstaller.ProfileInstallReceiver"
            android:permission="android.permission.DUMP" android:exported="true" />
    </application>
</manifest>"""


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


if __name__ == "__main__":
    unittest.main()
