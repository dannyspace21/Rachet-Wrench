#!/usr/bin/env python3
"""
build_brand.py — draws the Ratchet Wrench logo (an original design).

The mark is a ratchet wrench lying diagonally on a rounded orange tile:
  * a long handle with a hanging hole at its end,
  * a round ratchet head with a toothed gear and a square drive in the middle.

The tile has its own background colour, so the logo reads equally well on
light and dark pages, browser tabs and phone home screens.

All coordinates are computed here and written as plain paths (no transforms),
so the same geometry works in browsers, in the PDF report (fpdf2) and as an
Android adaptive-icon foreground.

Outputs (in brand/):
  logo-mark.svg      the app/web icon (tile + wrench)
  favicon.svg        same, for the browser tab
  logo-light.svg     mark + "Ratchet Wrench" wordmark, dark text (light pages)
  logo-dark.svg      mark + wordmark, white text (dark pages)
  android/app/src/main/res/drawable/ic_launcher_foreground.xml (if android/ exists)

Run:  python3 brand/build_brand.py
"""

from __future__ import annotations

import math
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

ORANGE = "#EA580C"  # tile
DEEP = "#9A3412"  # gear cut-out / hole
WHITE = "#FFFFFF"
INK = "#111827"

ANGLE = math.radians(-45)  # wrench tilted up to the right
CX, CY = 32.0, 32.0  # rotation centre (middle of the 64x64 tile)


def rot(x: float, y: float) -> tuple[float, float]:
    """Rotate a point about the tile centre."""
    dx, dy = x - CX, y - CY
    return (CX + dx * math.cos(ANGLE) - dy * math.sin(ANGLE), CY + dx * math.sin(ANGLE) + dy * math.cos(ANGLE))


def fmt(v: float) -> str:
    return f"{v:.2f}".rstrip("0").rstrip(".")


def pt(p: tuple[float, float]) -> str:
    return f"{fmt(p[0])} {fmt(p[1])}"


def circle_path(c: tuple[float, float], r: float) -> str:
    x, y = c
    return f"M{fmt(x - r)} {fmt(y)}a{fmt(r)} {fmt(r)} 0 1 0 {fmt(2 * r)} 0a{fmt(r)} {fmt(r)} 0 1 0 {fmt(-2 * r)} 0z"


def polygon(points: list[tuple[float, float]]) -> str:
    return "M" + "L".join(pt(p) for p in points) + "z"


def geometry() -> dict[str, str]:
    """Return the logo's path data, keyed by part name."""
    # Handle: a stadium (rounded bar) from x=9 to x=44 along y=32, 9 units thick.
    half = 4.5
    x0, x1 = 9.0, 44.0
    a, b = rot(x0, CY - half), rot(x1, CY - half)
    c, d = rot(x1, CY + half), rot(x0, CY + half)
    handle = f"M{pt(a)}L{pt(b)}L{pt(c)}L{pt(d)}A{fmt(half)} {fmt(half)} 0 0 1 {pt(a)}z"

    head_c = rot(44.0, CY)
    head = circle_path(head_c, 12.5)

    # Ratchet gear: 14 trapezoid teeth between radius 6.2 and 8.4.
    teeth = 14
    gear_pts = []
    for i in range(teeth):
        base = 2 * math.pi * i / teeth
        step = 2 * math.pi / teeth
        for frac, r in ((0.0, 6.2), (0.22, 8.4), (0.55, 8.4), (0.77, 6.2)):
            ang = base + frac * step
            gear_pts.append((head_c[0] + r * math.cos(ang), head_c[1] + r * math.sin(ang)))
    gear = polygon(gear_pts)

    # Square drive (aligned with the handle).
    s = 2.7
    sq = [rot(44 - s, CY - s), rot(44 + s, CY - s), rot(44 + s, CY + s), rot(44 - s, CY + s)]
    square = polygon(sq)

    # Direction switch: small pill on the head, toward the handle side.
    sw_c1, sw_c2 = rot(30.5, CY - 1.6), rot(30.5, CY + 1.6)
    switch = f"M{pt(sw_c1)}L{pt(sw_c2)}"

    hole = circle_path(rot(13.5, CY), 2.0)
    return {"handle": handle, "head": head, "gear": gear, "square": square, "switch": switch, "hole": hole}


def mark_paths(g: dict[str, str]) -> str:
    """The wrench drawn on top of the tile."""
    return (f'<path d="{g["handle"]}" fill="{WHITE}"/>'
            f'<path d="{g["head"]}" fill="{WHITE}"/>'
            f'<path d="{g["gear"]}" fill="{DEEP}"/>'
            f'<path d="{g["square"]}" fill="{WHITE}"/>'
            f'<path d="{g["hole"]}" fill="{DEEP}"/>'
            f'<path d="{g["switch"]}" stroke="{DEEP}" stroke-width="2.2" stroke-linecap="round" fill="none"/>')


def tile() -> str:
    return f'<path d="M14 0h36a14 14 0 0 1 14 14v36a14 14 0 0 1-14 14H14A14 14 0 0 1 0 50V14A14 14 0 0 1 14 0z" fill="{ORANGE}"/>'


def write_svgs(g: dict[str, str]) -> None:
    mark = (f'<svg xmlns="http://www.w3.org/2000/svg" width="64" height="64" viewBox="0 0 64 64">'
            f"<title>Ratchet Wrench</title>{tile()}{mark_paths(g)}</svg>\n")
    (ROOT / "brand" / "logo-mark.svg").write_text(mark, encoding="utf-8")
    (ROOT / "brand" / "favicon.svg").write_text(mark, encoding="utf-8")
    for name, color in (("logo-light.svg", INK), ("logo-dark.svg", WHITE)):
        full = (f'<svg xmlns="http://www.w3.org/2000/svg" width="360" height="64" viewBox="0 0 360 64">'
                f"<title>Ratchet Wrench</title>{tile()}{mark_paths(g)}"
                f'<text x="80" y="42" font-family="system-ui,-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif" '
                f'font-size="30" font-weight="700" letter-spacing="-0.5" fill="{color}">Ratchet Wrench</text></svg>\n')
        (ROOT / "brand" / name).write_text(full, encoding="utf-8")


def write_android(g: dict[str, str]) -> None:
    """Adaptive icon foreground: 108dp canvas, logo scaled into the 66dp safe zone."""
    res = ROOT / "android" / "app" / "src" / "main" / "res"
    if not (ROOT / "android").is_dir():
        return
    (res / "drawable").mkdir(parents=True, exist_ok=True)
    scale = 66 / 64 * 0.95
    offset = (108 - 64 * scale) / 2

    def p(d: str, fill: str = "", stroke: str = "") -> str:
        attrs = f'android:pathData="{d}"'
        attrs += f' android:fillColor="{fill}"' if fill else ""
        attrs += f' android:strokeColor="{stroke}" android:strokeWidth="2.2" android:strokeLineCap="round"' if stroke else ""
        return f"        <path {attrs}/>"

    body = "\n".join([p(g["handle"], WHITE), p(g["head"], WHITE), p(g["gear"], DEEP), p(g["square"], WHITE),
                      p(g["hole"], DEEP), p(g["switch"], stroke=DEEP)])
    xml = ('<?xml version="1.0" encoding="utf-8"?>\n<!-- Generated by brand/build_brand.py — do not edit by hand. -->\n'
           '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
           '    android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">\n'
           f'    <group android:scaleX="{scale:.4f}" android:scaleY="{scale:.4f}" android:translateX="{offset:.3f}" android:translateY="{offset:.3f}">\n'
           f"{body}\n    </group>\n</vector>\n")
    (res / "drawable" / "ic_launcher_foreground.xml").write_text(xml, encoding="utf-8")
    # Monochrome layer for Android 13+ themed icons (wrench only, one color).
    mono = xml.replace(f'android:fillColor="{DEEP}"', 'android:fillColor="#00000000"').replace(DEEP, "#FFFFFFFF")
    (res / "drawable" / "ic_launcher_monochrome.xml").write_text(mono, encoding="utf-8")


def main() -> None:
    g = geometry()
    write_svgs(g)
    write_android(g)
    print("brand assets written")


if __name__ == "__main__":
    main()
