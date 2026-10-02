"""Copy a verified canonical APK to its delivery name; verification stays upstream."""
import os
import re
import shutil
import sys
from pathlib import Path


def stage_apk(root: Path, variant: str) -> Path:
    if variant not in ('debug', 'release'):
        raise ValueError('variant must be debug or release')
    gradle = (root / 'app/build.gradle.kts').read_text()
    version = re.search(r'versionName = "(\d+\.\d+\.\d+)"', gradle).group(1)
    code = re.search(r'versionCode = (\d+)', gradle).group(1)
    name = (f'Rivet-{version}-code{code}-debug.apk' if variant == 'debug'
            else f'Rivet-v{version}.apk')
    source = root / f'app/build/outputs/apk/{variant}/app-{variant}.apk'
    destination = root / 'dist' / name
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, destination)
    return destination


if __name__ == '__main__':
    apk = stage_apk(Path('.'), sys.argv[1])
    print(apk)
    if os.environ.get('GITHUB_OUTPUT'):
        with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
            output.write(f'apk_path={apk}\n')
