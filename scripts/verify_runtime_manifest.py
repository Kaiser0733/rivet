"""Check packaged debug and release merged process-service boundaries."""

import os
import re
import subprocess
import sys
from pathlib import Path

from runtime_manifest import verify_apk_runtime_manifest, verify_release_runtime_manifest


def _aapt(sdk: Path) -> Path:
    candidates = sorted((sdk / "build-tools").glob("*/aapt"))
    if not candidates:
        raise AssertionError("Android SDK build-tools aapt was not found")
    return candidates[-1]


def _run(*args: str) -> str:
    return subprocess.check_output(args, text=True).strip()


def main() -> None:
    if len(sys.argv) != 3:
        sys.exit("Usage: verify_runtime_manifest.py <debug-apk> <app-build-directory>")
    apk, build = Path(sys.argv[1]), Path(sys.argv[2])
    sdk_value = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk_value:
        raise AssertionError("ANDROID_HOME or ANDROID_SDK_ROOT is required")
    aapt = _aapt(Path(sdk_value))
    badging = _run(str(aapt), "dump", "badging", str(apk)).splitlines()
    permissions = {
        match.group(1)
        for line in badging
        if (match := re.search(r"uses-permission(?:-sdk-\d+)?: name='([^']+)'", line))
    }
    debug_tree = _run(str(aapt), "dump", "xmltree", str(apk), "AndroidManifest.xml")
    verify_apk_runtime_manifest(debug_tree, permissions)

    intermediates = build / "intermediates"
    roots = [intermediates / "merged_manifest" / "release",
             intermediates / "merged_manifests" / "release"]
    manifests = [path for root in roots if root.is_dir() for path in root.rglob("AndroidManifest.xml")]
    assert len(manifests) == 1, "expected one release merged manifest"
    verify_release_runtime_manifest(manifests[0].read_text(encoding="utf-8"))
    print("debug APK and release merged process-service boundaries verified")


if __name__ == "__main__":
    main()
