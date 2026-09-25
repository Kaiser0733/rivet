"""Independent signer and tag checks for production APKs."""

import re
from pathlib import Path


def verify_release_identity(version_name: str, signer: str, pin_file: Path, tag: str) -> None:
    try:
        pinned = pin_file.read_text(encoding="ascii").strip()
    except FileNotFoundError as error:
        raise AssertionError("production signer is not pinned") from error
    assert re.fullmatch(r"[0-9a-fA-F]{64}", pinned), "production signer pin is invalid"
    assert signer.lower() == pinned.lower(), "APK signer differs from pinned production signer"
    assert not tag or tag == f"v{version_name}", (
        f"release tag {tag!r} does not match APK version v{version_name}"
    )
