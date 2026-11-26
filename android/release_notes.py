#!/usr/bin/env python3
"""
release_notes.py — used by the Android release workflow.

  python3 android/release_notes.py check 1.0.14
      Fails unless app/src/main/assets/release_notes.json has an entry for
      exactly this version AT THE TOP (newest first), with at least one note.
      So a release can never be published without release notes.

  python3 android/release_notes.py markdown 1.0.14
      Prints that version's notes as Markdown for the GitHub release page.
"""

import json
import sys
from pathlib import Path

NOTES = Path(__file__).resolve().parent / "app" / "src" / "main" / "assets" / "release_notes.json"
SECTIONS = (("new", "New"), ("improved", "Improved"), ("fixed", "Fixed"))


def load() -> list:
    return json.loads(NOTES.read_text(encoding="utf-8"))["releases"]


def check(version: str) -> int:
    releases = load()
    if not releases or releases[0].get("version") != version:
        top = releases[0].get("version") if releases else "nothing"
        print(f"::error::Release notes missing: the top entry of {NOTES.name} is {top}, "
              f"but this release is {version}. Add an entry for {version} at the top.")
        return 1
    if not any(releases[0].get(key) for key, _ in SECTIONS):
        print(f"::error::The release notes for {version} are empty.")
        return 1
    versions = [r.get("version") for r in releases]
    if len(versions) != len(set(versions)):
        print("::error::A version appears twice in the release notes.")
        return 1
    print(f"Release notes for {version}: OK")
    return 0


def markdown(version: str) -> int:
    release = next((r for r in load() if r.get("version") == version), None)
    if release is None:
        return 1
    for key, title in SECTIONS:
        items = release.get(key) or []
        if items:
            print(f"### {title}")
            for item in items:
                print(f"- {item}")
            print()
    return 0


if __name__ == "__main__":
    if len(sys.argv) != 3 or sys.argv[1] not in ("check", "markdown"):
        print(__doc__)
        sys.exit(2)
    sys.exit(check(sys.argv[2]) if sys.argv[1] == "check" else markdown(sys.argv[2]))
