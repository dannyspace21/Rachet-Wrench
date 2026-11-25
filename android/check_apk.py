#!/usr/bin/env python3
"""
check_apk.py — sanity checks on a built release APK, run by the release
workflow before anything is published.

Why: the code shrinker (R8) once removed the constructors of ML Kit's
"ComponentRegistrar" classes.  ML Kit creates those by name, so the app
crashed as soon as the VIN barcode scanner opened.  This script reads the
APK's manifest and code and fails if any component that Android or ML Kit
creates BY NAME is missing its no-argument constructor.

It also checks that every icon drawable made by icons/build_icons.py is
still inside the APK.  Version 1.0.10 shipped with the resource shrinker
having deleted them (they were looked up by name), so the home screen and
the Add-entry screen crashed on open.

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
    failures = check_icons(apk, tools)
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


def check_icons(apk: Path, tools: Path) -> list:
    """Every icons/types/*.svg and icons/ui/*.svg must exist as a drawable."""
    root = Path(__file__).resolve().parent.parent / "icons"
    wanted = {f"ic_type_{p.stem}" for p in (root / "types").glob("*.svg")}
    wanted |= {f"ic_ui_{p.stem}" for p in (root / "ui").glob("*.svg")}
    wanted |= {"ic_launcher_foreground"}
    # Reviewed: fixed SDK program + file path as a list, no shell.
    res = subprocess.run([str(tools / "aapt2"), "dump", "resources", str(apk)],  # nosemgrep
                         capture_output=True, text=True, check=True).stdout
    present = set(re.findall(r"drawable/([a-z0-9_]+)", res))
    missing = sorted(wanted - present)
    print(f"icons: {len(wanted) - len(missing)} of {len(wanted)} present")
    return [f"drawable {m} missing (resource shrinker removed it?)" for m in missing]


if __name__ == "__main__":
    sys.exit(main())
