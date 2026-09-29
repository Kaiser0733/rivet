"""Validate the app-owned process service and its narrow Android capability set."""

import re
from xml.etree import ElementTree

ANDROID = "{http://schemas.android.com/apk/res/android}"
EXPECTED_SERVICE = "com.kaiser.rivet.runtime.PersistentProcessService"
EXISTING_STARTUP_PROVIDER = "androidx.startup.InitializationProvider"
EXISTING_PROFILE_RECEIVER = "androidx.profileinstaller.ProfileInstallReceiver"
ALLOWED_PERMISSIONS = {
    "android.permission.INTERNET",
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
    "com.kaiser.rivet.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
}
REQUIRED_PERMISSIONS = {
    "android.permission.INTERNET",
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
}
FORBIDDEN_PERMISSIONS = {
    "android.permission.MANAGE_EXTERNAL_STORAGE",
    "android.permission.READ_EXTERNAL_STORAGE",
    "android.permission.WRITE_EXTERNAL_STORAGE",
    "android.permission.REQUEST_INSTALL_PACKAGES",
    "android.permission.QUERY_ALL_PACKAGES",
    "android.permission.BIND_ACCESSIBILITY_SERVICE",
    "android.permission.SYSTEM_ALERT_WINDOW",
    "android.permission.WAKE_LOCK",
    "android.permission.RECEIVE_BOOT_COMPLETED",
}


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


def _attribute(section: str, name: str) -> str | None:
    values = [line.split("=", 1)[1].strip() for line in section.splitlines()
              if line.lstrip().startswith("A:") and name in line]
    if not values:
        return None
    assert len(values) == 1, f"expected one {name} attribute"
    return values[0]


def _is_false(value: str | None) -> bool:
    return value is not None and (value.endswith("0x00000000") or value.endswith("false"))


def verify_apk_runtime_manifest(xmltree: str, permissions: set[str]) -> None:
    assert REQUIRED_PERMISSIONS <= permissions, "APK lacks foreground service permissions"
    assert permissions <= ALLOWED_PERMISSIONS, f"unexpected APK permissions: {sorted(permissions - ALLOWED_PERMISSIONS)}"
    assert not permissions & FORBIDDEN_PERMISSIONS, f"forbidden APK permissions: {sorted(permissions & FORBIDDEN_PERMISSIONS)}"

    services = _elements(xmltree, "service")
    matches = [service for service in services if EXPECTED_SERVICE in service]
    assert len(matches) == 1 and len(services) == 1, "APK must declare only Rivet's process service"
    service = matches[0]
    assert _is_false(_attribute(service, "android:exported")), "process service must be non-exported"
    service_type = _attribute(service, "android:foregroundServiceType") or ""
    assert service_type.endswith("0x40000000") or service_type.endswith("specialUse"), (
        "process service must use only foregroundServiceType= specialUse"
    )
    properties = _elements(service, "property")
    subtype = [item for item in properties if "PROPERTY_SPECIAL_USE_FGS_SUBTYPE" in item]
    assert len(subtype) == 1, "specialUse service must declare its subtype"
    value = _attribute(subtype[0], "android:value") or ""
    assert "loopback-only preview" in value.lower(), "specialUse subtype must describe local preview use"
    assert "BOOT_COMPLETED" not in xmltree, "process service must not resume on boot"
    receivers = _elements(xmltree, "receiver")
    providers = _elements(xmltree, "provider")
    assert len(receivers) == 1 and EXISTING_PROFILE_RECEIVER in receivers[0], (
        "only the existing AndroidX profile receiver may be present"
    )
    receiver_permission = _attribute(receivers[0], "android:permission") or ""
    assert "android.permission.DUMP" in receiver_permission, (
        "the existing AndroidX profile receiver must remain protected by DUMP"
    )
    assert len(providers) == 1 and EXISTING_STARTUP_PROVIDER in providers[0], (
        "only the existing AndroidX startup provider may be present"
    )


def verify_release_runtime_manifest(manifest_xml: str) -> None:
    try:
        manifest = ElementTree.fromstring(manifest_xml)
    except ElementTree.ParseError as error:
        raise AssertionError("invalid release merged manifest") from error
    permissions = {node.get(ANDROID + "name") for node in manifest.findall("uses-permission")}
    assert REQUIRED_PERMISSIONS <= permissions, "release merged manifest lacks foreground service permissions"
    assert permissions <= ALLOWED_PERMISSIONS, f"unexpected release permissions: {sorted(permissions - ALLOWED_PERMISSIONS)}"
    assert not permissions & FORBIDDEN_PERMISSIONS, f"forbidden release permissions: {sorted(permissions & FORBIDDEN_PERMISSIONS)}"

    application = manifest.find("application")
    assert application is not None
    services = application.findall("service")
    assert len(services) == 1, "release must declare only Rivet's process service"
    service = services[0]
    assert service.get(ANDROID + "name", "").endswith(".runtime.PersistentProcessService"), (
        "release process service identity changed"
    )
    assert service.get(ANDROID + "exported") == "false", "release process service must be non-exported"
    assert service.get(ANDROID + "foregroundServiceType") == "specialUse", (
        "release process service must use only specialUse"
    )
    subtypes = [node for node in service.findall("property")
                if node.get(ANDROID + "name") == "android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"]
    assert len(subtypes) == 1 and "loopback-only preview" in subtypes[0].get(ANDROID + "value", "").lower(), (
        "release specialUse subtype must describe local preview use"
    )
    assert not any(node.get(ANDROID + "name") == "android.intent.action.BOOT_COMPLETED"
                   for node in manifest.iter()), "release must not resume processes on boot"
    receivers = application.findall("receiver")
    providers = application.findall("provider")
    assert len(receivers) == 1 and receivers[0].get(ANDROID + "name", "").endswith(
        ".profileinstaller.ProfileInstallReceiver"), "release may contain only the existing AndroidX profile receiver"
    assert receivers[0].get(ANDROID + "permission") == "android.permission.DUMP"
    assert len(providers) == 1 and providers[0].get(ANDROID + "name", "").endswith(
        ".startup.InitializationProvider"), "release may contain only the existing AndroidX startup provider"
