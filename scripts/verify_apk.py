"""Validate the built APK artifact, not just Gradle declarations (runs in CI).

Usage: verify_apk.py <apk> <keystore> <storepass> <alias>
Fails when package identity, version, required INTERNET permission, or the
signer certificate do not match what the build declared.
"""
import hashlib
import os
import re
import subprocess
import sys
from pathlib import Path

if len(sys.argv) != 5:
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
permission = "uses-permission: name='android.permission.INTERNET'"
assert permission in badging_lines, f"APK missing required permission: {permission}"
for forbidden in ("MANAGE_EXTERNAL_STORAGE", "READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE"):
    assert not any(f"android.permission.{forbidden}" in line for line in badging_lines), f"Forbidden permission: {forbidden}"
print("broad-storage permissions: absent")

signing = run(str(tools / "apksigner"), "verify", "--print-certs", apk)
certificate = subprocess.check_output(
    ["keytool", "-exportcert", "-keystore", keystore,
     "-storepass", storepass, "-alias", alias])
expected_signer = hashlib.sha256(certificate).hexdigest()
digests = [line.split("certificate SHA-256 digest: ", 1)[1]
           for line in signing.splitlines()
           if "certificate SHA-256 digest: " in line]
assert digests and set(digests) == {expected_signer}, signing

print(badging)
print(permission)
print("signer SHA-256:", expected_signer)
print("apk SHA-256:", hashlib.sha256(Path(apk).read_bytes()).hexdigest())
