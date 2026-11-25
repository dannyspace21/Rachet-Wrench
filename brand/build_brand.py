#!/usr/bin/env python3
"""
build_brand.py — draws the Ratchet Wrench logo (an original design).

The mark shows TWO tools crossed like an "X" on a rounded orange tile:
  * a RATCHET running from bottom-left to top-right: a round head (top
    right) with a toothed ratchet gear, a square drive and a direction switch, plus a
    handle with a hanging hole at its end;
  * an open-end WRENCH running from top-left to bottom-right: an open jaw
    at the top-left end and a hexagonal box end at the bottom-right.
The ratchet lies on top.  A thin orange "gap" outline around it separates
the two tools where they overlap, so both shapes stay readable even at
favicon size.

The tile has its own background colour, so the logo reads equally well on
light and dark pages, browser tabs and phone home screens.

All coordinates are computed here and written as plain paths (no
transforms), so the same geometry works in browsers, in the PDF report
(fpdf2) and as an Android adaptive-icon foreground.

Outputs (in brand/):
  logo-mark.svg      the app/web icon (tile + crossed tools)
  favicon.svg        same, for the browser tab
  logo-light.svg     mark + "Ratchet Wrench" wordmark, dark text (light pages)
  logo-dark.svg      mark + wordmark, white text (dark pages)
  android/app/src/main/res/drawable/ic_launcher_foreground.xml and
  ic_launcher_monochrome.xml (if android/ exists)

Run:  python3 brand/build_brand.py
"""

from __future__ import annotations

import math
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

ORANGE = "#EA580C"  # tile, and the "gap" between the crossed tools
DEEP = "#9A3412"  # gear and hole cut-outs
WHITE = "#FFFFFF"
INK = "#111827"

CX, CY = 32.0, 32.0  # centre of the 64x64 tile (both tools cross here)
RATCHET_ANGLE = math.radians(-45)  # ratchet head points to the top-right
WRENCH_ANGLE = math.radians(45)  # wrench's open jaw points to the top-left


def rot(x: float, y: float, angle: float) -> tuple[float, float]:
    """Rotate a point about the tile centre."""
    dx, dy = x - CX, y - CY
    return (CX + dx * math.cos(angle) - dy * math.sin(angle), CY + dx * math.sin(angle) + dy * math.cos(angle))


def fmt(v: float) -> str:
    return f"{v:.2f}".rstrip("0").rstrip(".")


def pt(p: tuple[float, float]) -> str:
    return f"{fmt(p[0])} {fmt(p[1])}"


def circle_path(c: tuple[float, float], r: float) -> str:
    x, y = c
    return f"M{fmt(x - r)} {fmt(y)}a{fmt(r)} {fmt(r)} 0 1 0 {fmt(2 * r)} 0a{fmt(r)} {fmt(r)} 0 1 0 {fmt(-2 * r)} 0z"


def polygon(points: list[tuple[float, float]]) -> str:
    return "M" + "L".join(pt(p) for p in points) + "z"


def stadium(x0: float, x1: float, half: float, angle: float) -> str:
    """A rounded bar (tool handle) from x0 to x1 along the centre line."""
    a, b = rot(x0, CY - half, angle), rot(x1, CY - half, angle)
    c, d = rot(x1, CY + half, angle), rot(x0, CY + half, angle)
    r = fmt(half)
    return f"M{pt(a)}L{pt(b)}A{r} {r} 0 0 1 {pt(c)}L{pt(d)}A{r} {r} 0 0 1 {pt(a)}z"


# A "layer" is (kind, path, colour): kind "fill" paints the shape, kind
# "stroke" draws its outline (used for the gap and the direction switch).
Layer = tuple[str, str, str, float]


def layers() -> list[Layer]:
    """The whole drawing, bottom layer first."""
    out: list[Layer] = []

    # ---- 1. The open-end wrench (underneath) -----------------------------
    w = WRENCH_ANGLE
    out.append(("fill", stadium(15, 49, 3.3, w), WHITE, 0))
    out.append(("fill", circle_path(rot(13, CY, w), 7.4), WHITE, 0))  # open-jaw head
    out.append(("fill", circle_path(rot(50.5, CY, w), 6.4), WHITE, 0))  # box-end head
    # Open jaw: a slot cut into the head, opening outward (tile colour).
    jaw = [rot(3.5, CY - 3.1, w), rot(13.6, CY - 3.1, w), rot(13.6, CY + 3.1, w), rot(3.5, CY + 3.1, w)]
    out.append(("fill", polygon(jaw), ORANGE, 0))
    # Box end: hexagonal hole.
    bx = rot(50.5, CY, w)
    hexagon = [(bx[0] + 3.2 * math.cos(math.radians(60 * i + 15)), bx[1] + 3.2 * math.sin(math.radians(60 * i + 15))) for i in range(6)]
    out.append(("fill", polygon(hexagon), DEEP, 0))

    # ---- 2. The ratchet (on top), with an orange gap outline -------------
    r = RATCHET_ANGLE
    handle = stadium(11, 43, 4.2, r)
    head_c = rot(43, CY, r)
    head = circle_path(head_c, 11.2)
    for shape in (handle, head):  # the gap: a thick orange outline first
        out.append(("stroke", shape, ORANGE, 3.2))
    out.append(("fill", handle, WHITE, 0))
    out.append(("fill", head, WHITE, 0))

    # Ratchet gear: 14 trapezoid teeth between radius 5.4 and 7.4.
    teeth = 14
    gear_pts = []
    for i in range(teeth):
        base = 2 * math.pi * i / teeth
        step = 2 * math.pi / teeth
        for frac, rad in ((0.0, 5.4), (0.22, 7.4), (0.55, 7.4), (0.77, 5.4)):
            ang = base + frac * step
            gear_pts.append((head_c[0] + rad * math.cos(ang), head_c[1] + rad * math.sin(ang)))
    out.append(("fill", polygon(gear_pts), DEEP, 0))
    s = 2.4  # square drive, aligned with the handle
    out.append(("fill", polygon([rot(43 - s, CY - s, r), rot(43 + s, CY - s, r), rot(43 + s, CY + s, r), rot(43 - s, CY + s, r)]), WHITE, 0))
    # Direction switch (small bar where the head meets the handle).
    out.append(("stroke", f"M{pt(rot(31, CY - 1.5, r))}L{pt(rot(31, CY + 1.5, r))}", DEEP, 2.0))
    out.append(("fill", circle_path(rot(15, CY, r), 1.8), DEEP, 0))  # hanging hole
    return out


def svg_layers(ls: list[Layer]) -> str:
    parts = []
    for kind, d, color, width in ls:
        if kind == "fill":
            parts.append(f'<path d="{d}" fill="{color}"/>')
        else:
            parts.append(f'<path d="{d}" fill="none" stroke="{color}" stroke-width="{width}" stroke-linecap="round" stroke-linejoin="round"/>')
    return "".join(parts)


def tile() -> str:
    return f'<path d="M14 0h36a14 14 0 0 1 14 14v36a14 14 0 0 1-14 14H14A14 14 0 0 1 0 50V14A14 14 0 0 1 14 0z" fill="{ORANGE}"/>'


def write_svgs(ls: list[Layer]) -> None:
    mark = (f'<svg xmlns="http://www.w3.org/2000/svg" width="64" height="64" viewBox="0 0 64 64">'
            f"<title>Ratchet Wrench</title>{tile()}{svg_layers(ls)}</svg>\n")
    (ROOT / "brand" / "logo-mark.svg").write_text(mark, encoding="utf-8")
    (ROOT / "brand" / "favicon.svg").write_text(mark, encoding="utf-8")
    for name, color in (("logo-light.svg", INK), ("logo-dark.svg", WHITE)):
        full = (f'<svg xmlns="http://www.w3.org/2000/svg" width="360" height="64" viewBox="0 0 360 64">'
                f"<title>Ratchet Wrench</title>{tile()}{svg_layers(ls)}"
                f'<text x="80" y="42" font-family="system-ui,-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif" '
                f'font-size="30" font-weight="700" letter-spacing="-0.5" fill="{color}">Ratchet Wrench</text></svg>\n')
        (ROOT / "brand" / name).write_text(full, encoding="utf-8")


def write_android(ls: list[Layer]) -> None:
    """Adaptive icon foreground: 108dp canvas, logo scaled into the 66dp safe zone."""
    res = ROOT / "android" / "app" / "src" / "main" / "res"
    if not (ROOT / "android").is_dir():
        return
    (res / "drawable").mkdir(parents=True, exist_ok=True)
    scale = 66 / 64 * 0.95
    offset = (108 - 64 * scale) / 2

    def vector(body_layers: list[Layer], mono: bool) -> str:
        rows = []
        for kind, d, color, width in body_layers:
            if mono:
                # Themed (monochrome) icon: one colour; cut-outs and gaps become see-through.
                if color != WHITE:
                    continue
                color = "#FFFFFFFF"
            if kind == "fill":
                rows.append(f'        <path android:pathData="{d}" android:fillColor="{color}"/>')
            else:
                rows.append(f'        <path android:pathData="{d}" android:strokeColor="{color}" android:strokeWidth="{width}" '
                            f'android:strokeLineCap="round" android:strokeLineJoin="round"/>')
        return ('<?xml version="1.0" encoding="utf-8"?>\n<!-- Generated by brand/build_brand.py — do not edit by hand. -->\n'
                '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
                '    android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">\n'
                f'    <group android:scaleX="{scale:.4f}" android:scaleY="{scale:.4f}" android:translateX="{offset:.3f}" android:translateY="{offset:.3f}">\n'
                + "\n".join(rows) + "\n    </group>\n</vector>\n")

    (res / "drawable" / "ic_launcher_foreground.xml").write_text(vector(ls, False), encoding="utf-8")
    (res / "drawable" / "ic_launcher_monochrome.xml").write_text(vector(ls, True), encoding="utf-8")


def main() -> None:
    ls = layers()
    write_svgs(ls)
    write_android(ls)
    print("brand assets written")


if __name__ == "__main__":
    main()
