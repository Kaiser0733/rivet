"""Validate the built APK artifact, not just Gradle declarations (runs in CI).

Usage: verify_apk.py <apk> <keystore> <storepass> <alias> [<production-pin-file> <release-tag>]
Fails when APK identity, SDK levels, permissions, bundled notices, or signer
do not match the release configuration.
"""
import hashlib
import os
import re
import subprocess
import sys
from pathlib import Path
from zipfile import ZipFile
from release_identity import verify_release_identity

if len(sys.argv) not in (5, 7):
    sys.exit(__doc__)
apk, keystore, storepass, alias = sys.argv[1:5]


def run(*args):
    return subprocess.check_output(args, text=True).strip()


def required(pattern, text):
    match = re.search(pattern, text)
    assert match, f"pattern not found in app/build.gradle.kts: {pattern}"
    return match.group(1)


gradle = Path("app/build.gradle.kts").read_text()
version_code = required(r"versionCode = (\d+)", gradle)
version_name = required(r'versionName = "([^"]*)"', gradle)
min_sdk = int(required(r"minSdk = (\d+)", gradle))
target_sdk = int(required(r"targetSdk = (\d+)", gradle))
compile_sdk = int(required(r"compileSdk = (\d+)", gradle))

sdk = Path(os.environ.get("ANDROID_HOME") or os.environ["ANDROID_SDK_ROOT"])
tools = sorted((sdk / "build-tools").glob("*/aapt"))[-1].parent
badging_lines = run(str(tools / "aapt"), "dump", "badging", apk).splitlines()
badging = badging_lines[0]
for expected in (
    "name='com.kaiser.rivet'",
    f"versionCode='{version_code}'",
    f"versionName='{version_name}'",
):
    assert expected in badging, f"badging mismatch: expected {expected} in {badging}"
artifact_min_sdk = int(required(r"sdkVersion:'(\d+)'", "\n".join(badging_lines)))
artifact_target_sdk = int(required(r"targetSdkVersion:'(\d+)'", "\n".join(badging_lines)))
artifact_compile_sdk = int(required(r"compileSdkVersion='(\d+)'", badging))
assert artifact_min_sdk == min_sdk == 26, f"minSdk mismatch: APK={artifact_min_sdk}, Gradle={min_sdk}"
assert artifact_target_sdk == target_sdk and artifact_target_sdk >= 35, (
    f"targetSdk mismatch or below 35: APK={artifact_target_sdk}, Gradle={target_sdk}"
)
assert artifact_compile_sdk == compile_sdk and artifact_compile_sdk >= 35, (
    f"compileSdk mismatch or below 35: APK={artifact_compile_sdk}, Gradle={compile_sdk}"
)

requested = {
    match.group(1)
    for line in badging_lines
    if (match := re.search(r"uses-permission(?:-sdk-\d+)?: name='([^']+)'", line))
}
allowed_permissions = {
    "android.permission.INTERNET",
    # AndroidX adds its signature-protected helper for non-exported receivers.
    "com.kaiser.rivet.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
}
assert "android.permission.INTERNET" in requested, "APK is missing INTERNET"
assert requested <= allowed_permissions, f"Unexpected APK permissions: {sorted(requested - allowed_permissions)}"
manifest = run(str(tools / "aapt"), "dump", "xmltree", apk, "AndroidManifest.xml")
for forbidden in (
    "android.permission.BIND_ACCESSIBILITY_SERVICE",
    "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE",
    "android.permission.BIND_VPN_SERVICE",
    "android.permission.PACKAGE_USAGE_STATS",
):
    assert forbidden not in manifest, f"Forbidden manifest capability: {forbidden}"
print("APK permissions and SDK levels verified")

with ZipFile(apk) as artifact:
    for name in ("assets/LICENSE", "assets/THIRD_PARTY_NOTICES.md"):
        assert name in artifact.namelist(), f"APK is missing {name}"
    notices = artifact.read("assets/THIRD_PARTY_NOTICES.md").decode("utf-8")
    assert "WcWidth.java" in notices and "termux/termux-app" in notices
print("third-party notices included")

signing = run(str(tools / "apksigner"), "verify", "--print-certs", apk)
certificate = subprocess.check_output(
    ["keytool", "-exportcert", "-keystore", keystore,
     "-storepass", storepass, "-alias", alias])
expected_signer = hashlib.sha256(certificate).hexdigest()
digests = [line.split("certificate SHA-256 digest: ", 1)[1]
           for line in signing.splitlines()
           if "certificate SHA-256 digest: " in line]
assert digests and set(digests) == {expected_signer}, signing
if len(sys.argv) == 7:
    verify_release_identity(version_name, expected_signer, Path(sys.argv[5]), sys.argv[6])

print(badging)
print("permissions:", ", ".join(sorted(requested)))
print("signer SHA-256:", expected_signer)
print("apk SHA-256:", hashlib.sha256(Path(apk).read_bytes()).hexdigest())
