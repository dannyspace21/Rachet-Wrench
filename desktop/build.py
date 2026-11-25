#!/usr/bin/env python3
"""
build.py — packages the Ratchet Wrench DESKTOP app with PyInstaller.

Produces, in desktop/dist/:
  Windows: RatchetWrench/RatchetWrench.exe   (a folder; zip it to share)
  macOS:   RatchetWrench.app
  Linux:   RatchetWrench/RatchetWrench

Everything needed is bundled: Python, the server code, the web app, icons,
logo and catalog.  No Docker, no internet and no separate Python install
are needed to run it.

Usage (from the repository root, with the server's packages installed):
    pip install --require-hashes -r server/requirements.txt
    pip install --require-hashes -r desktop/requirements-desktop.txt
    python desktop/build.py

PyInstaller is the standard, widely used tool for this (GPL with an
explicit exception that allows distributing the apps it builds).
"""

from __future__ import annotations

import os
import sys
from pathlib import Path

import PyInstaller.__main__

ROOT = Path(__file__).resolve().parents[1]
SEP = os.pathsep  # PyInstaller's --add-data separator (";" on Windows, ":" elsewhere)


def data(src: str, dest: str) -> str:
    return f"--add-data={ROOT / src}{SEP}{dest}"


def main() -> None:
    args = [
        str(ROOT / "desktop" / "launcher.py"),
        "--name=RatchetWrench",
        "--onedir",
        "--windowed",            # no black console window on Windows/macOS
        "--noconfirm",
        "--clean",
        f"--icon={ROOT / 'desktop' / 'icon.png'}",
        f"--paths={ROOT / 'server'}",
        f"--distpath={ROOT / 'desktop' / 'dist'}",
        f"--workpath={ROOT / 'desktop' / 'build'}",
        f"--specpath={ROOT / 'desktop' / 'build'}",
        "--osx-bundle-identifier=com.ratchetwrench.desktop",
        # Our own server package and everything it imports lazily.
        "--collect-submodules=app",
        "--hidden-import=app.pdf_worker",
        "--collect-submodules=uvicorn",
        "--collect-all=pillow_heif",
        "--collect-data=fpdf",
        "--hidden-import=anthropic",  # optional AI vision mode
        # Shared files the server serves or reads.
        data("web", "web"),
        data("icons", "icons"),
        data("brand", "brand"),
        data("catalog/catalog.json", "catalog"),
        data("server/app/static", "server/app/static"),
        data("server/app/static", "app/static"),
        data("desktop/icon.png", "desktop"),
    ]
    print("Building Ratchet Wrench desktop for", sys.platform)
    PyInstaller.__main__.run(args)


if __name__ == "__main__":
    main()
