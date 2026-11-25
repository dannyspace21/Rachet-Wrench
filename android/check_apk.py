#!/usr/bin/env python3
"""
check_apk.py — sanity checks on a built release APK, run by the release
workflow before anything is published.

Why: the code shrinker (R8) once removed the constructors of ML Kit's
"ComponentRegistrar" classes.  ML Kit creates those by name, so the app
crashed as soon as the VIN barcode scanner opened.  This script reads the
APK's manifest and code and fails if any component that Android or ML Kit
creates BY NAME is missing its no-argument constructor.

Usage: python3 android/check_apk.py <app-release.apk> <android-build-tools-dir>
"""

import re
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path


def main() -> int:
    apk, tools = Path(sys.argv[1]), Path(sys.argv[2])
    # Reviewed: build-machine tool; fixed SDK program + file paths as a list, no shell.
    manifest = subprocess.run([str(tools / "aapt2"), "dump", "xmltree", "--file", "AndroidManifest.xml", str(apk)],  # nosemgrep
                              capture_output=True, text=True, check=True).stdout
    # Components ML Kit (and Firebase-style discovery) instantiate by name.
    registrars = set(re.findall(r'"com\.google\.firebase\.components:([A-Za-z0-9_.$]+)"', manifest))
    # The CameraX config provider is also created by name.
    registrars |= set(re.findall(r'value\(0x01010024\)="(androidx\.camera\.[A-Za-z0-9_.$]+Provider)"', manifest))
    if not registrars:
        print("No by-name components found (unexpected).")
        return 1

    dump = []
    with tempfile.TemporaryDirectory() as tmp, zipfile.ZipFile(apk) as z:
        for name in z.namelist():
            if re.fullmatch(r"classes\d*\.dex", name):
                path = z.extract(name, tmp)
                # Reviewed: fixed SDK program + extracted file path, no shell.
                dump.append(subprocess.run([str(tools / "dexdump"), path], capture_output=True, text=True).stdout)  # nosemgrep
    text = "\n".join(dump)

    failures = []
    for cls in sorted(registrars):
        descriptor = "L" + cls.replace(".", "/") + ";"
        start = text.find(f"Class descriptor  : '{descriptor}'")
        if start < 0:
            failures.append(f"{cls}: class missing")
            continue
        end = text.find("Class descriptor  :", start + 10)
        block = text[start:end if end > 0 else None]
        if "'<init>'" not in block:
            failures.append(f"{cls}: no constructor (R8 removed it)")
        else:
            print(f"ok  {cls}")
    for f in failures:
        print(f"FAIL {f}")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
