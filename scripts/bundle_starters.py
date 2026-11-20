"""Build the bundled starter archives and refresh their index sizes."""
import json
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "app/src/main/assets/starters"


def main():
    index = ASSETS / "starters.json"
    catalog = json.loads(index.read_text(encoding="utf-8"))
    for entry in catalog["starters"]:
        source = ROOT / "starters" / entry["id"]
        destination = ASSETS / entry["zip"]
        with ZipFile(destination, "w") as archive:
            for file in sorted(path for path in source.rglob("*") if path.is_file()):
                member = ZipInfo(file.relative_to(source).as_posix())
                member.compress_type = ZIP_DEFLATED
                archive.writestr(member, file.read_bytes())
        entry["bytes"] = destination.stat().st_size
    index.write_text(json.dumps(catalog, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
