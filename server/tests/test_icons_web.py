"""
Tests for the icon set, logo and web app files (Phase 2).
"""

import re
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def test_every_type_has_a_valid_icon(client):
    cat = client.get("/api/catalog").json()
    for t in cat["types"]:
        path = ROOT / "icons" / "types" / t["icon"]
        assert path.is_file(), t["key"]
        root = ET.fromstring(path.read_text())  # well-formed SVG
        assert root.get("viewBox") == "0 0 24 24" and root.get("stroke-width") == "2"
        assert all(el.tag.endswith(("path", "title")) for el in root.iter() if el is not root)
    assert any(t["key"] == "custom" for t in cat["types"])


def test_icons_are_unique():
    seen = {}
    for p in (ROOT / "icons" / "types").glob("*.svg"):
        body = re.sub(r"<title>.*?</title>", "", p.read_text())
        assert body not in seen, f"{p.stem} looks identical to {seen.get(body)}"
        seen[body] = p.stem


def test_logo_and_sprite_served(client):
    for path in ("/brand/logo-mark.svg", "/brand/favicon.svg", "/icons/sprite.svg", "/icons/types/oil_change.svg"):
        r = client.get(path)
        assert r.status_code == 200, path


def test_web_app_has_no_inline_scripts_or_styles(client):
    r = client.get("/")
    assert r.status_code == 200
    assert "default-src 'self'" in r.headers["content-security-policy"]
    html = r.text
    assert not re.search(r"<script(?![^>]*\bsrc=)", html), "inline <script> found"
    assert "style=" not in html and "<style" not in html
    for js in (ROOT / "web").rglob("*.js"):
        text = js.read_text()
        assert ".innerHTML" not in text and "eval(" not in text and "new Function" not in text, js.name
