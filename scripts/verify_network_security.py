"""Check that cleartext exceptions exist only in the packaged debug variant."""

import os
import re
import subprocess
import sys
from pathlib import Path
from xml.etree import ElementTree
from zipfile import ZipFile

ANDROID = "{http://schemas.android.com/apk/res/android}"


def _elements(xmltree: str, tag: str) -> list[str]:
    lines = xmltree.splitlines()
    sections = []
    for index, line in enumerate(lines):
        match = re.match(r"^( *)E: ([\w-]+)(?:\s|\(|$)", line)
        if not match or match.group(2) != tag:
            continue
        indent = len(match.group(1))
        end = len(lines)
        for next_index in range(index + 1, len(lines)):
            next_element = re.match(r"^( *)E: [\w-]+(?:\s|\(|$)", lines[next_index])
            if next_element and len(next_element.group(1)) <= indent:
                end = next_index
                break
        sections.append("\n".join(lines[index:end]))
    return sections


def _boolean_attribute(section: str, attribute: str) -> bool:
    values = [line.rsplit("=", 1)[-1].strip().lower()
              for line in section.splitlines() if attribute in line and line.lstrip().startswith("A:")]
    assert len(values) == 1, f"expected one {attribute} attribute"
    value = values[0]
    if value.endswith("0xffffffff") or value.endswith("true"):
        return True
    if re.search(r"0x0+$", value) or value.endswith("false"):
        return False
    raise AssertionError(f"unrecognized {attribute} value: {value}")


def verify_debug_network_security(manifest_tree: str, config_tree: str,
                                  resource_paths: set[str], resources_dump: str) -> None:
    references = [line for line in manifest_tree.splitlines()
                  if "android:networkSecurityConfig" in line and line.lstrip().startswith("A:")]
    assert len(references) == 1, "debug manifest must reference one network security config"
    resource_id = re.search(r"=@(0x[0-9a-fA-F]+)\b", references[0])
    assert resource_id and re.search(
        rf"resource\s+{re.escape(resource_id.group(1))}\s+[^\n]*:xml/network_security_config\b",
        resources_dump,
    ), "debug manifest must reference the packaged network security resource"
    global_cleartext = [line for line in manifest_tree.splitlines()
                        if "android:usesCleartextTraffic" in line and line.lstrip().startswith("A:")]
    assert not global_cleartext, "debug manifest must not set global cleartext traffic"
    assert "res/xml/network_security_config.xml" in resource_paths, "debug APK lacks network security resource"
    assert len(_elements(config_tree, "network-security-config")) == 1, "invalid debug network security resource"

    element_names = [match.group(1) for line in config_tree.splitlines()
                     if (match := re.match(r"^\s*E: ([\w-]+)(?:\s|\(|$)", line))]
    assert element_names == ["network-security-config", "base-config"], (
        "debug network policy must contain only one base-config"
    )
    bases = _elements(config_tree, "base-config")
    assert len(bases) == 1 and _boolean_attribute(
        bases[0], "cleartextTrafficPermitted"
    ), "debug base-config must allow cleartext for physical mock-provider testing"

    cleartext_attributes = [line for line in config_tree.splitlines()
                            if "cleartextTrafficPermitted" in line and line.lstrip().startswith("A:")]
    assert len(cleartext_attributes) == 1, "unexpected cleartext policy scope"
    config_attributes = [line for line in config_tree.splitlines()
                         if line.lstrip().startswith("A:")]
    assert config_attributes == cleartext_attributes, (
        "debug network policy must not add certificate trust or unrelated attributes"
    )


def verify_release_network_exclusion(manifest_xml: str, merged_resource_paths: set[str]) -> None:
    try:
        manifest = ElementTree.fromstring(manifest_xml)
    except ElementTree.ParseError as error:
        raise AssertionError("invalid release merged manifest") from error
    application = manifest.find("application")
    assert application is not None
    assert not application.get(ANDROID + "networkSecurityConfig"), (
        "release manifest references the debug network security config"
    )
    global_cleartext = application.get(ANDROID + "usesCleartextTraffic")
    assert global_cleartext is None or global_cleartext.lower() == "false", (
        "release manifest enables global cleartext traffic"
    )
    assert not any("network_security_config" in path.lower() for path in merged_resource_paths), (
        "release resources include the debug network security config"
    )


def _aapt(sdk: Path) -> Path:
    candidates = sorted((sdk / "build-tools").glob("*/aapt"))
    if not candidates:
        raise AssertionError("Android SDK build-tools aapt was not found")
    return candidates[-1]


def _run(*args: str) -> str:
    return subprocess.check_output(args, text=True).strip()


def _release_outputs(build: Path) -> tuple[Path, set[str]]:
    intermediates = build / "intermediates"
    manifest_roots = [intermediates / "merged_manifest" / "release",
                      intermediates / "merged_manifests" / "release"]
    manifests = [path for root in manifest_roots if root.is_dir()
                 for path in root.rglob("AndroidManifest.xml")]
    assert len(manifests) == 1, "expected one release merged manifest"
    resources_root = intermediates / "merged_res" / "release"
    assert resources_root.is_dir(), "release merged resources were not built"
    resources = {path.relative_to(resources_root).as_posix()
                 for path in resources_root.rglob("*") if path.is_file()}
    return manifests[0], resources


def main() -> None:
    if len(sys.argv) != 3:
        sys.exit("Usage: verify_network_security.py <debug-apk> <app-build-directory>")
    apk, build = Path(sys.argv[1]), Path(sys.argv[2])
    sdk_value = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk_value:
        raise AssertionError("ANDROID_HOME or ANDROID_SDK_ROOT is required")
    aapt = _aapt(Path(sdk_value))
    with ZipFile(apk) as artifact:
        resources = set(artifact.namelist())
    manifest_tree = _run(str(aapt), "dump", "xmltree", str(apk), "AndroidManifest.xml")
    config_tree = _run(str(aapt), "dump", "xmltree", str(apk), "res/xml/network_security_config.xml")
    resources_dump = _run(str(aapt), "dump", "resources", str(apk))
    verify_debug_network_security(manifest_tree, config_tree, resources, resources_dump)
    release_manifest, release_resources = _release_outputs(build)
    verify_release_network_exclusion(release_manifest.read_text(encoding="utf-8"), release_resources)
    print("debug loopback network policy and release exclusion verified")


if __name__ == "__main__":
    main()
