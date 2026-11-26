#!/usr/bin/env python3
"""
release_notes.py — used by the Android release workflow.

  python3 android/release_notes.py check 1.0.14
      Fails unless app/src/main/assets/release_notes.json has an entry for
      exactly this version AT THE TOP (newest first), with at least one note.
      So a release can never be published without release notes.

  python3 android/release_notes.py markdown 1.0.14
  python3 android/release_notes.py markdown 1.0.26 --since 1.0.23   (adds 1.0.24, 1.0.25)
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


def _patch(version: str) -> int:
    try:
        return int(version.rsplit(".", 1)[1])
    except (IndexError, ValueError):
        return -1


def _print(release: dict, level: str = "###") -> None:
    for key, title in SECTIONS:
        items = release.get(key) or []
        if items:
            print(f"{level} {title}")
            for item in items:
                print(f"- {item}")
            print()


def markdown(version: str, since: str | None = None) -> int:
    """Notes for `version`, plus (with --since X) every version after X that
    was never published on its own (e.g. a build GitHub cancelled), so the
    release page lists everything new since the last release."""
    releases = load()
    release = next((r for r in releases if r.get("version") == version), None)
    if release is None:
        return 1
    _print(release)
    if since:
        earlier = [r for r in releases if _patch(since) < _patch(r.get("version", "")) < _patch(version)]
        for r in earlier:  # newest first, like the file
            print(f"### Also in this update (from {r['version']})")
            print()
            _print(r, "####")
    return 0


if __name__ == "__main__":
    args = sys.argv[1:]
    since_arg = None
    if len(args) == 4 and args[2] == "--since":
        since_arg = args[3] or None
        args = args[:2]
    if len(args) != 2 or args[0] not in ("check", "markdown"):
        print(__doc__)
        sys.exit(2)
    sys.exit(check(args[1]) if args[0] == "check" else markdown(args[1], since_arg))
