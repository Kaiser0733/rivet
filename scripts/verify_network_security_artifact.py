#!/usr/bin/env python3
"""Prove the compiled loopback network config is inside a final APK.

No source fallback: the APK's resources are searched for the compiled XML, then
aapt renders that binary XML from the artifact. The shipped 1.4.3 APK is the
red-side fixture for the gate.
"""
from __future__ import annotations

import os
import re
import subprocess
import sys
import zipfile
from pathlib import Path


def _aapt() -> str:
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk:
        raise SystemExit("ANDROID_HOME or ANDROID_SDK_ROOT is required")
    candidates = sorted(Path(sdk, "build-tools").glob("*/aapt"))
    if not candidates:
        raise SystemExit("Android SDK build-tools aapt was not found")
    return str(candidates[-1])


def find_config(apk: Path) -> str:
    with zipfile.ZipFile(apk) as archive:
        for name in archive.namelist():
            if not (name.startswith("res/") and name.endswith(".xml")):
                continue
            data = archive.read(name)
            if (b"network-security-config" in data and b"127.0.0.1" in data
                    and b"localhost" in data and b"::1" in data):
                return name
    raise AssertionError("compiled network-security-config XML is missing from the APK")


def verify(apk: Path) -> None:
    config = find_config(apk)
    tree = subprocess.check_output([_aapt(), "dump", "xmltree", str(apk), config],
                                   text=True)
    assert "E: network-security-config" in tree, "APK config has the wrong root"
    base = re.search(r"E: base-config.*?A: cleartextTrafficPermitted=.*?0x0",
                     tree, re.S)
    assert base is not None, "APK base-config must deny cleartext"
    domains = set(re.findall(r'C: "([^"]+)"', tree))
    assert domains == {"127.0.0.1", "localhost", "::1"}, (
        f"APK cleartext allowlist is wrong: {sorted(domains)}")
    assert tree.count("E: domain-config") == 1, "APK must have one loopback domain-config"
    print(f"packaged network config verified from {apk.name}:{config}")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("Usage: verify_network_security_artifact.py <apk>")
    verify(Path(sys.argv[1]))
