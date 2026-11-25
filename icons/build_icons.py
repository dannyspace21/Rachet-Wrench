#!/usr/bin/env python3
"""
build_icons.py — draws the complete Ratchet Wrench icon set.

One consistent style for every icon:
  * 24 x 24 grid, drawing kept inside 2..22
  * 2 px strokes, round line ends and round joins, no fills
  * corner radius 2 on boxes, color = "currentColor" (follows light/dark theme)

Every shape is written as an SVG <path>, never <circle>/<rect>, so the very
same drawing converts 1:1 into Android VectorDrawable XML (Android vectors
only understand paths).  The script writes:

  icons/types/<type_key>.svg   one icon per maintenance/repair type
  icons/ui/<name>.svg          interface icons (menu, camera, bell, ...)
  android/app/src/main/res/drawable/ic_type_<key>.xml, ic_ui_<name>.xml

All drawings are original, made for this project.  The visual conventions
(24 px grid, 2 px round strokes) follow the open-source Lucide icon style
(ISC license); see DECISIONS.md.

Run:  python3 icons/build_icons.py
"""

from __future__ import annotations

import json
import math
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

# ---------------------------------------------------------------------------
# Drawing helpers — each returns SVG path data ("d" attribute text).
# ---------------------------------------------------------------------------


def f(n: float) -> str:
    """Format a number compactly (2.0 -> '2', 2.50 -> '2.5')."""
    s = f"{n:.2f}".rstrip("0").rstrip(".")
    return "0" if s in ("-0", "") else s


def circle(cx: float, cy: float, r: float) -> str:
    """A circle drawn as two half-circle arcs."""
    return f"M{f(cx - r)} {f(cy)}a{f(r)} {f(r)} 0 1 0 {f(2 * r)} 0a{f(r)} {f(r)} 0 1 0 {f(-2 * r)} 0z"


def rect(x: float, y: float, w: float, h: float, r: float = 2) -> str:
    """A rounded rectangle."""
    r = min(r, w / 2, h / 2)
    if r <= 0:
        return f"M{f(x)} {f(y)}h{f(w)}v{f(h)}h{f(-w)}z"
    return (f"M{f(x + r)} {f(y)}h{f(w - 2 * r)}a{f(r)} {f(r)} 0 0 1 {f(r)} {f(r)}v{f(h - 2 * r)}"
            f"a{f(r)} {f(r)} 0 0 1 {f(-r)} {f(r)}h{f(-(w - 2 * r))}a{f(r)} {f(r)} 0 0 1 {f(-r)} {f(-r)}"
            f"v{f(-(h - 2 * r))}a{f(r)} {f(r)} 0 0 1 {f(r)} {f(-r)}z")


def line(*pts: float) -> str:
    """A straight polyline through (x1,y1,x2,y2,...)."""
    out = f"M{f(pts[0])} {f(pts[1])}"
    for i in range(2, len(pts), 2):
        out += f"L{f(pts[i])} {f(pts[i + 1])}"
    return out


def dot(x: float, y: float) -> str:
    """A tiny dot (a zero-length line with round caps)."""
    return f"M{f(x)} {f(y)}h.01"


def drop(cx: float, cy: float, s: float = 1.0) -> str:
    """A liquid droplet centred at (cx, cy); s scales it (1 = about 7 px tall)."""
    top = cy - 3.6 * s
    return (f"M{f(cx)} {f(top)}c{f(0)} {f(0)} {f(-2.6 * s)} {f(2.9 * s)} {f(-2.6 * s)} {f(4.6 * s)}"
            f"a{f(2.6 * s)} {f(2.6 * s)} 0 0 0 {f(5.2 * s)} 0c0 {f(-1.7 * s)} {f(-2.6 * s)} {f(-4.6 * s)} {f(-2.6 * s)} {f(-4.6 * s)}z")


def radial_ticks(cx: float, cy: float, r1: float, r2: float, n: int, offset: float = 0) -> str:
    """n short lines pointing outward from radius r1 to r2 (gear teeth etc.)."""
    parts = []
    for i in range(n):
        a = math.radians(offset + 360 * i / n)
        parts.append(line(cx + r1 * math.cos(a), cy + r1 * math.sin(a), cx + r2 * math.cos(a), cy + r2 * math.sin(a)))
    return "".join(parts)


def ring_of_circles(cx: float, cy: float, r: float, small: float, n: int, offset: float = -90) -> str:
    """n small circles evenly spaced on a ring (bearing balls, bolt holes)."""
    return "".join(circle(cx + r * math.cos(math.radians(offset + 360 * i / n)),
                          cy + r * math.sin(math.radians(offset + 360 * i / n)), small) for i in range(n))


def zigzag(x1: float, x2: float, y: float, step: float, n: int) -> str:
    """A coil-spring zigzag going down from y, alternating x1/x2."""
    out = f"M{f(x1)} {f(y)}"
    for i in range(n):
        out += f"L{f(x2 if i % 2 == 0 else x1)} {f(y + step * (i + 1))}"
    return out


def plus_badge(cx: float, cy: float, s: float = 2.5) -> str:
    return line(cx, cy - s, cx, cy + s) + line(cx - s, cy, cx + s, cy)


def link(x: float, y: float, w: float = 9, h: float = 6) -> str:
    """One chain link (stadium shape)."""
    return rect(x, y, w, h, h / 2)


def car_side(y: float = 0) -> str:
    """A compact side view of a car body (used in a few icons)."""
    return (f"M3 {f(16 + y)}v{f(-3)}l2.2-4.6A2 2 0 0 1 7 {f(7 + y)}h10a2 2 0 0 1 1.8 1.4L21 {f(13 + y)}v3"
            + line(3, 16 + y, 5, 16 + y) + line(10, 16 + y, 14, 16 + y) + line(19, 16 + y, 21, 16 + y)
            + circle(7.5, 16.5 + y, 2) + circle(16.5, 16.5 + y, 2) + line(4, 12 + y, 20, 12 + y))


# ---------------------------------------------------------------------------
# Maintenance & repair type icons.  key -> list of path strings.
# Each comment says what the drawing shows.
# ---------------------------------------------------------------------------
TYPE_ICONS: dict[str, list[str]] = {
    # Oil can with spout and a falling drop
    "oil_change": ["M3 11h10l2.5 3.5V19H3z", line(15.5, 14.5, 21, 11.5), "M6 11V8.5h4V11", drop(20, 17.2, 0.6)],
    # Spin-on oil filter: canister with ribs and threaded top
    "oil_filter": [rect(6, 6, 12, 16, 2.5), "M9 6V3.5h6V6", line(9.5, 9.5, 9.5, 18.5), line(12, 9.5, 12, 18.5), line(14.5, 9.5, 14.5, 18.5)],
    # Engine air filter: pleated panel
    "air_filter": [rect(3, 6, 18, 12, 2), "M6 9l1.5 6L9 9l1.5 6L12 9l1.5 6L15 9l1.5 6L18 9"],
    # Cabin filter: small pleated panel above swirling air
    "cabin_filter": [rect(4, 2.5, 16, 8, 2), "M7 4.5l1.25 4L9.5 4.5l1.25 4L12 4.5l1.25 4 1.25-4 1.25 4L17 4.5",
                     "M3 15h10.5a2 2 0 1 0-2-2", "M6 19h11a2 2 0 1 1-2 2"],
    # Inline fuel filter with hose ends and a fuel drop inside
    "fuel_filter": [line(2, 12, 6, 12), line(18, 12, 22, 12), rect(6, 7, 12, 10, 2.5), drop(12, 12.4, 0.62)],
    # Spark plug: terminal, insulator, hex, threads, electrode + spark
    "spark_plugs": [line(12, 2, 12, 4), rect(10, 4, 4, 4, 1), "M8.5 8h7v3h-7z", "M10 11h4v5h-4z", line(10, 13.5, 14, 13.5),
                    "M12 16v2.5h2", line(17.5, 17, 19.5, 16), line(17.5, 20, 19.5, 21)],
    # Ignition coil pack with a lightning bolt
    "ignition_coils": [rect(5, 2.5, 14, 7, 2), "M10 9.5v3h4v-3", "M12 12.5V15", "M13 15l-3 4h4l-3 4"],
    # Serpentine belt wrapped around three pulleys
    "serpentine_belt": ["M6 3.5h12a3.5 3.5 0 0 1 3 5.3L15.1 18.6a3.6 3.6 0 0 1-6.2 0L3 8.8a3.5 3.5 0 0 1 3-5.3z",
                        circle(6, 7, 1.4), circle(18, 7, 1.4), circle(12, 16.8, 1.6)],
    # Timing belt: tall toothed belt around two pulleys
    "timing_belt": [rect(6, 2, 12, 20, 6), circle(12, 8, 2.5), circle(12, 16, 2.5), line(4.5, 9, 6, 9), line(4.5, 12, 6, 12),
                    line(4.5, 15, 6, 15), line(18, 9, 19.5, 9), line(18, 12, 19.5, 12), line(18, 15, 19.5, 15)],
    # Timing chain: chain loop with link pins around two sprockets
    "timing_chain": [rect(2, 6.5, 20, 11, 5.5), circle(7.5, 12, 2.5), circle(16.5, 12, 2.5),
                     line(10, 5, 10, 8), line(14, 5, 14, 8), line(10, 16, 10, 19), line(14, 16, 14, 19)],
    # Valve adjustment: poppet valve with spring and a clearance gap
    "valve_adjustment": [line(12, 2, 12, 15), zigzag(9, 15, 4, 1.5, 6), "M6.5 18c0-1.8 2.2-3 5.5-3s5.5 1.2 5.5 3z",
                         line(5, 21.5, 19, 21.5)],
    # PCV valve: one-way valve body with flow arrow
    "pcv_valve": [line(2.5, 12, 7, 12), rect(7, 8, 8, 8, 2), line(15, 12, 21.5, 12), "M18.5 9l3 3-3 3", line(9.5, 10, 12.5, 14)],
    # Throttle body: round bore with butterfly plate and lever
    "throttle_body": [circle(11, 12, 8), line(6.5, 16.5, 15.5, 7.5), circle(11, 12, 1.2), line(19, 12, 22, 12), line(22, 9.5, 22, 14.5)],
    # Fuel system cleaning: injector spraying, with a sparkle
    "fuel_system_cleaning": ["M9 2h4v3.5H9z", "M8 5.5h6l-1 7.5h-4z", "M10 13h2v3.5h-2z", line(11, 16.5, 11, 21),
                             line(9, 18, 7.5, 21), line(13, 18, 14.5, 21), line(19, 3, 19, 8), line(16.5, 5.5, 21.5, 5.5)],
    # Engine block sitting on two rubber mounts
    "engine_mounts": [rect(4, 5, 16, 9, 2), "M7 5V3h10v2", line(8, 9.5, 16, 9.5), rect(5, 16.5, 4, 3, 1), rect(15, 16.5, 4, 3, 1),
                      line(7, 14, 7, 16.5), line(17, 14, 17, 16.5), line(2.5, 21.5, 21.5, 21.5)],
    # Coolant: dashboard coolant symbol (thermometer in waves)
    "coolant": ["M12 2.5a2 2 0 0 0-2 2v8.3a3.6 3.6 0 1 0 4 0V4.5a2 2 0 0 0-2-2z", line(12, 8, 14.5, 8), line(12, 5.5, 14.5, 5.5),
                "M2 19.5c1 .7 2 .7 3 0s2-.7 3 0", "M16 19.5c1 .7 2 .7 3 0s2-.7 3 0"],
    # Radiator: core with vertical fins and two hose necks
    "radiator": [rect(3, 5, 18, 13, 2), line(7, 7.5, 7, 15.5), line(10, 7.5, 10, 15.5), line(13, 7.5, 13, 15.5), line(16, 7.5, 16, 15.5),
                 line(18, 5, 18, 2.5), line(6, 18, 6, 21.5)],
    # Water pump: impeller with vanes and an outlet pipe
    "water_pump": [circle(10, 13, 7.5), circle(10, 13, 1.5), "M10 11.5c0-2 1-3.5 3-4", "M11.4 13.8c1.8 1 2.6 2.6 2.4 4.6",
                   "M8.6 13.8c-1.8 1-3.5 1-5-.2", "M15 7.5h6.5", "M17.3 10.5h4.2"],
    # Thermostat: thermometer inside a round housing
    "thermostat": [circle(12, 12, 9.5), "M12 6a1.5 1.5 0 0 0-1.5 1.5v5.3a2.6 2.6 0 1 0 3 0V7.5A1.5 1.5 0 0 0 12 6z", line(12, 10, 12, 14.5)],
    # Hoses: a bent hose with clamps at both ends
    "hoses": ["M3 21c0-6 3-9 9-9 4 0 6-2 6-8", "M7.5 21c0-3.5 1.5-5 4.5-5 6.5 0 10-3.5 10-11.5", line(2.5, 18.5, 8, 18.5), line(17, 6, 22, 6)],
    # Transmission fluid: gear with a drop
    "transmission_fluid": [circle(10, 14, 5), circle(10, 14, 1.8), radial_ticks(10, 14, 5, 7.3, 8, 22.5), drop(19, 6, 0.75)],
    # Brake fluid: brake warning symbol with a drop in the centre
    "brake_fluid": [circle(12, 12, 6.5), "M4 6.5a9 9 0 0 0 0 11", "M20 6.5a9 9 0 0 1 0 11", drop(12, 12.3, 0.62)],
    # Clutch fluid: reservoir with level mark and a drop
    "clutch_fluid": [rect(6, 7.5, 12, 14, 2.5), "M9 7.5V4h6v3.5", line(6, 11.5, 18, 11.5), drop(12, 16.8, 0.65)],
    # Power steering fluid: steering wheel with a drop
    "power_steering_fluid": [circle(10, 14, 7.5), circle(10, 14, 2), line(10, 16, 10, 21.5), line(8.1, 13.3, 2.8, 11.6), line(11.9, 13.3, 17.2, 11.6), drop(19.5, 5, 0.7)],
    # Differential fluid: differential housing with axles and a drop
    "differential_fluid": [circle(11, 14, 5), line(2, 14, 6, 14), line(16, 14, 22, 14), line(11, 9, 11, 5), circle(11, 14, 1.5), drop(18.5, 5.5, 0.65)],
    # Transfer case fluid: box with one input and two output shafts + drop
    "transfer_case_fluid": [rect(7, 6, 10, 13, 2), line(2, 9, 7, 9), line(17, 8.5, 22, 8.5), line(17, 16.5, 22, 16.5), drop(12, 12.8, 0.65)],
    # Final drive (shaft drive) oil: wheel hub with drive shaft and a drop
    "final_drive_oil": [circle(15, 14, 6.5), circle(15, 14, 2), line(2, 10, 9, 10), line(9, 10, 11, 11.5), drop(5, 17.5, 0.65)],
    # Washer fluid: windshield with spray jets
    "washer_fluid": ["M2.5 19L5 7h14l2.5 12z", line(12, 19, 12, 16), line(12, 16, 8, 10.5), line(12, 16, 16, 10.5), line(12, 16, 12, 10)],
    # Tire rotation: tire inside circular arrows
    "tire_rotation": [circle(12, 12, 4), circle(12, 12, 1.5), "M20 12a8 8 0 0 0-13.7-5.6L4 8.5", "M4 3.5v5h5",
                      "M4 12a8 8 0 0 0 13.7 5.6L20 15.5", "M20 20.5v-5h-5"],
    # Tire tread depth: depth gauge probing a tread groove
    "tire_tread_depth": ["M2 20h5v-5.5h3V20h4v-5.5h3V20h5", rect(10, 2.5, 4, 6.5, 1), line(12, 9, 12, 20), line(7, 14.5, 17, 14.5),
                         line(14, 4.5, 15.5, 4.5), line(14, 6.8, 15.5, 6.8)],
    # Tire pressure: TPMS warning symbol (tire cross-section with "!")
    "tire_pressure": ["M5.6 18.5A9 9 0 0 1 3.5 13c0-2 .7-3.6 1.6-5L7 4.5h10L18.9 8c.9 1.4 1.6 3 1.6 5a9 9 0 0 1-2.1 5.5",
                      line(5, 21.5, 6, 19.5), line(9, 21.5, 9.5, 19.5), line(15, 21.5, 14.5, 19.5), line(19, 21.5, 18, 19.5),
                      line(12, 8.5, 12, 13), dot(12, 16.5)],
    # Tire replacement: tire with tread marks and a "new" plus
    "tire_replacement": [circle(10, 13, 8), circle(10, 13, 3.5), radial_ticks(10, 13, 5.5, 7, 8, 22.5), plus_badge(19.5, 4.5)],
    # Flat repair: tire with a nail sticking in
    "tire_repair": [circle(11, 13, 8), circle(11, 13, 3), line(15.5, 8.5, 20.5, 3.5), line(19, 2, 22, 5)],
    # Wheel alignment: two wheels from above with parallel guide arrows
    "wheel_alignment": [rect(3, 3, 4, 9, 1.5), rect(17, 3, 4, 9, 1.5), line(7, 7.5, 17, 7.5), line(5, 15.5, 5, 21.5), line(19, 15.5, 19, 21.5),
                        line(5, 18.5, 19, 18.5), "M7.5 16.5L5 18.5l2.5 2", "M16.5 16.5l2.5 2-2.5 2"],
    # Wheel balancing: wheel with a clip-on weight and balance marks
    "wheel_balance": [circle(12, 13, 8), circle(12, 13, 2.5), rect(10.5, 3.6, 3, 2.4, .6), line(2, 13, 4, 13), line(20, 13, 22, 13),
                      line(12, 15.5, 12, 21), line(9.6, 12.2, 4.4, 10.6), line(14.4, 12.2, 19.6, 10.6)],
    # TPMS sensor: valve stem sensor sending radio waves
    "tpms_sensor": [rect(3.5, 13, 9, 7, 2), line(8, 13, 8, 5), rect(6.8, 2.5, 2.4, 2.5, .5), "M15 8.5a4.5 4.5 0 0 1 0 6", "M18 6.5a8 8 0 0 1 0 10"],
    # Wheel bearing: ball bearing (races + balls)
    "wheel_bearings": [circle(12, 12, 9.5), circle(12, 12, 3.5), ring_of_circles(12, 12, 6.5, 1.3, 7)],
    # Brake pads: two pads either side of the rotor edge
    "brake_pads": [rect(3, 4, 4, 16, 1.5), "M7 6.5h2.5v11H7", rect(17, 4, 4, 16, 1.5), "M17 6.5h-2.5v11H17", line(12, 2.5, 12, 21.5)],
    # Brake rotor: drilled disc with hub and caliper
    "brake_rotors": [circle(11, 13, 8.5), circle(11, 13, 3), ring_of_circles(11, 13, 5.8, .55, 8, 0),
                     "M14.5 3.5a10 10 0 0 1 6 5.5l-2.6 1.3a7.2 7.2 0 0 0-4.3-4z"],
    # Brake caliper: caliper body with pistons over the disc edge
    "brake_calipers": ["M11 2.5a9.5 9.5 0 0 0 0 19", rect(10, 4, 11, 16, 3), circle(15.5, 9, 1.6), circle(15.5, 15, 1.6)],
    # Brake lines: hard line with fittings at both ends
    "brake_lines": [rect(2, 3.5, 4, 5, 1), "M6 6h4a3 3 0 0 1 3 3v6a3 3 0 0 0 3 3h2", circle(20, 18, 2), line(20, 14, 20, 16)],
    # Battery with + and - terminals
    "battery": [rect(2.5, 7, 19, 13, 2), "M6 7V4.5h3V7", "M15 7V4.5h3V7", line(5.5, 13.5, 9.5, 13.5), line(7.5, 11.5, 7.5, 15.5), line(14.5, 13.5, 18.5, 13.5)],
    # Alternator: finned body, pulley and an AC wave
    "alternator": [circle(14, 13, 7.5), circle(4.5, 13, 2.5), line(7, 13, 6.5, 13), "M10.5 13c1-1.6 2.3-1.6 3.5 0s2.5 1.6 3.5 0",
                   line(11, 3, 11, 5.6), line(14, 3, 14, 5.5), line(17, 3, 17, 5.6)],
    # Starter motor: motor can with solenoid on top and pinion gear
    "starter": [rect(2.5, 10, 13, 9, 2), rect(4.5, 4, 9, 6, 1.5), line(15.5, 14.5, 19, 14.5), line(19, 11, 19, 18), line(21.5, 12, 21.5, 17)],
    # Lights: headlight high-beam symbol
    "lights_bulbs": ["M10 5C6 5 3.5 8 3.5 12s2.5 7 6.5 7c1.5 0 2-1 2-7s-.5-7-2-7z", line(15, 7, 21, 7), line(15, 10.3, 21, 10.3),
                     line(15, 13.7, 21, 13.7), line(15, 17, 21, 17)],
    # Fuses: blade fuse with its metal element
    "fuses": [rect(5, 2.5, 14, 11.5, 2), line(8.5, 14, 8.5, 21.5), line(15.5, 14, 15.5, 21.5), "M8.5 8.5H10l1.2-2 1.6 4 1.2-2h1.5"],
    # Diagnostics: check-engine light (engine outline)
    "diagnostics": ["M6 6.5h6", line(9, 6.5, 9, 9), "M5 9h11l2 2h1.5V9H22v8h-2.5v-2H18l-2 3H8l-2-2H5z", line(5, 12.5, 2, 12.5), line(2, 10.5, 2, 14.5)],
    # Suspension: coil spring between two seats
    "suspension": [line(7, 3, 17, 3), line(12, 3, 12, 5), zigzag(8.5, 15.5, 5, 1.6, 8), line(7, 19.5, 17, 19.5), line(12, 19.5, 12, 22)],
    # Shocks/struts: shock absorber (eyes, rod, body)
    "shocks_struts": [circle(12, 3.5, 1.5), line(12, 5, 12, 9), rect(8.5, 9, 7, 10, 1.5), line(8.5, 12, 15.5, 12), circle(12, 20.5, 1.5)],
    # Steering: steering wheel
    "steering": [circle(12, 12, 9.5), circle(12, 12, 2.5), line(12, 14.5, 12, 21.5), line(9.6, 11.3, 2.8, 9.5), line(14.4, 11.3, 21.2, 9.5)],
    # CV axle: shaft with accordion boots at both ends
    "cv_axles": [line(2, 12, 4.5, 12), "M4.5 8.5l2 3.5-2 3.5", "M7 8.5l2 3.5-2 3.5", line(9, 12, 15, 12), "M19.5 8.5l-2 3.5 2 3.5",
                 "M17 8.5l-2 3.5 2 3.5", line(19.5, 12, 22, 12)],
    # Clutch: clutch disc with friction ring and damper springs
    "clutch": [circle(12, 12, 9.5), circle(12, 12, 6.5), circle(12, 12, 2), rect(10.8, 6.3, 2.4, 1.6, .4), rect(10.8, 16.1, 2.4, 1.6, .4),
               rect(6.3, 10.8, 1.6, 2.4, .4), rect(16.1, 10.8, 1.6, 2.4, .4)],
    # Exhaust: muffler, tailpipe and smoke puffs
    "exhaust": [rect(2, 10, 12, 7, 3.5), line(14, 13.5, 18, 13.5), "M19.5 10.5c1-1 1-2 0-3", "M21.5 11.5c1.3-1.6 1.3-3.4 0-5"],
    # Catalytic converter: honeycomb core between pipes
    "catalytic_converter": [line(2, 12, 5, 12), line(19, 12, 22, 12), "M5 8l2-2.5h10L19 8v8l-2 2.5H7L5 16z", line(9, 8.5, 9, 15.5),
                            line(12, 8.5, 12, 15.5), line(15, 8.5, 15, 15.5), line(7.5, 12, 16.5, 12)],
    # A/C: snowflake
    "ac_service": [line(12, 2, 12, 22), line(3.3, 7, 20.7, 17), line(20.7, 7, 3.3, 17), "M9.5 3.5L12 6l2.5-2.5", "M9.5 20.5L12 18l2.5 2.5",
                   "M3.6 10.3l3.4-.9-.9-3.4", "M20.4 13.7l-3.4.9.9 3.4", "M20.4 10.3l-3.4-.9.9-3.4", "M3.6 13.7l3.4.9-.9 3.4"],
    # Wipers: windshield arc with wiper arm and blade
    "wipers": ["M2 19a10 10 0 0 1 20 0", line(12, 19, 6.5, 8), line(4.5, 9.5, 8, 6.5), circle(12, 19, 1.3)],
    # Glass: windshield with a crack
    "glass": ["M2.5 19L5.5 6h13l3 13z", "M13 8.5l-1.5 3 2 1-2 3.5", line(13.5, 12.5, 16, 12.5)],
    # Body/paint: spray paint can with spray dots
    "body_paint": [rect(5, 9, 9, 13, 2), "M7.5 9V6.5h4V9", line(9.5, 6.5, 9.5, 4), dot(16.5, 4), dot(19, 2.5), dot(19, 5.5), dot(21.5, 4), dot(17.5, 7)],
    # Detailing/wash: car with sparkles
    "detailing": [car_side(3), line(18.5, 2, 18.5, 6), line(16.5, 4, 20.5, 4), line(11, 2.5, 11, 5), line(9.75, 3.75, 12.25, 3.75)],
    # Locks/keys: key fob with buttons and key ring
    "locks_keys": [rect(7, 6, 10, 16, 4), circle(12, 11, 1.6), circle(12, 16.5, 1.6), circle(12, 3.5, 1.8)],
    # Chain adjustment: two chain links with a "slack" double arrow
    "chain_adjustment": [link(2.5, 4, 10, 6.5), link(11.5, 4, 10, 6.5), line(5, 18, 19, 18), "M7.5 15.5L5 18l2.5 2.5", "M16.5 15.5L19 18l-2.5 2.5"],
    # Chain lube: chain links with an oil drop above
    "chain_lube": [link(2, 13, 10.5, 7), link(11.5, 13, 10.5, 7), drop(12, 6, 0.85)],
    # Chain replacement: chain links with a circular "replace" arrow
    "chain_replacement": [link(2, 12.5, 10.5, 7), link(11.5, 12.5, 10.5, 7), "M5 8.5A7.5 5 0 0 1 18.5 6", "M19 2.5v4h-4"],
    # Sprocket: toothed sprocket with bolt holes
    "sprockets": [circle(12, 12, 7.5), radial_ticks(12, 12, 7.5, 9.8, 12), circle(12, 12, 2.2), ring_of_circles(12, 12, 4.8, .6, 5)],
    # Rear shock service: coil-over shock with a small wrench
    "shock_maintenance": [circle(9, 3.5, 1.5), line(9, 5, 9, 20), circle(9, 21, 1.2), zigzag(5.5, 12.5, 6, 1.6, 8),
                          "M18.5 6.5a2.5 2.5 0 0 1 2.6 3.4l-4.6 4.6a1 1 0 0 1-1.5-1.5l4.6-4.6a2.5 2.5 0 0 1-1.1-1.9"],
    # Fork oil: front fork (two legs, triple clamp) with a drop
    "fork_oil": [line(6, 2, 6, 11), line(18, 2, 18, 11), line(4, 5, 20, 5), rect(4.5, 11, 3, 9.5, 1.5), rect(16.5, 11, 3, 9.5, 1.5),
                 line(7.5, 19, 16.5, 19), drop(12, 12, 0.7)],
    # Fork seals: one fork leg with the seal ring and a weep
    "fork_seals": [line(12, 2, 12, 10), rect(8.5, 12, 7, 10, 2), rect(8, 9.5, 8, 2.5, 1.2), drop(19, 15, 0.55)],
    # Steering head bearings: handlebar on top of a bearing
    "steering_head_bearings": ["M2.5 5.5c2 0 3 1.5 5 1.5h9c2 0 3-1.5 5-1.5", line(12, 7, 12, 10.5), circle(12, 16, 5.5), circle(12, 16, 2),
                               ring_of_circles(12, 16, 3.8, .55, 6)],
    # Cables: handlebar lever pulling a curved cable
    "cables": [circle(4, 5, 1.5), line(5.4, 5.6, 11, 8), "M11 8c4 1.5 1.5 7 5.5 9s4.5-1 5.5-3", line(15.5, 15.8, 18, 18.6)],
    # Levers/controls: grip with brake lever
    "brake_lever": [rect(2, 3.5, 9, 4.5, 2.25), circle(12.5, 6, 1.6), line(13.5, 7.3, 21.5, 13.5), line(11, 8, 12, 12)],
    # Inspection: clipboard with a check mark
    "inspection": [rect(5, 4, 14, 18, 2), "M9 4V2.5h6V4", "M8.5 13l2.5 2.5 4.5-5"],
    # Emissions: exhaust cloud with a check mark
    "emissions": ["M17.5 19H8a6 6 0 1 1 5.7-8h1.3a4 4 0 0 1 2.5 8z", "M8.5 14.5l2 2 3.5-3.5"],
    # Registration: license plate
    "registration": [rect(2, 6, 20, 12, 2), line(6, 12.5, 9, 12.5), line(11, 12.5, 14, 12.5), line(16, 12.5, 18, 12.5), dot(5.5, 9), dot(18.5, 9)],
    # Insurance: shield with check
    "insurance": ["M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z", "M8.5 12l2.5 2.5 4.5-4.5"],
    # Recall: warning triangle with "!"
    "recall": ["M10.3 3.9L2 18a2 2 0 0 0 1.7 3h16.6a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z", line(12, 9, 12, 13.5), dot(12, 17)],
    # Accident/damage: impact burst
    "accident_damage": ["M12 2l1.6 5.2 4.6-2.7-2.2 4.8 5.5 1.2-5 2.2 3 4.4-5-1.8L12 20.5l-1.5-5.2-5 1.8 3-4.4-5-2.2 5.5-1.2L6.8 4.5l4.6 2.7z"],
    # General repair: wrench
    "general_repair": ["M20.5 7a5 5 0 0 1-6.6 4.7l-7.3 7.3a2.1 2.1 0 0 1-3-3l7.3-7.3A5 5 0 0 1 17 2.5l-3 3 .5 3.5 3.5.5z"],
    # Custom/other: rounded square with a plus
    "custom": [rect(3, 3, 18, 18, 4), line(12, 8, 12, 16), line(8, 12, 16, 12)],
}

# ---------------------------------------------------------------------------
# Interface icons (same style).
# ---------------------------------------------------------------------------
UI_ICONS: dict[str, list[str]] = {
    "home": ["M3 10.5L12 3l9 7.5", "M5 9v12h14V9", "M10 21v-6h4v6"],
    "car": [car_side(-1)],
    "motorcycle": [circle(5.5, 16, 3.5), circle(18.5, 16, 3.5), "M5.5 16l4-6h5l4 6", "M9.5 10l-1-2.5H6", "M14.5 10l1.5-3h2.5", "M10 13h4"],
    "plus": [line(12, 5, 12, 19), line(5, 12, 19, 12)],
    "camera": ["M4 7h3l2-2.5h6L17 7h3a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V9a2 2 0 0 1 2-2z", circle(12, 13, 3.5)],
    "upload": ["M12 15V3", "M7 8l5-5 5 5", "M4 15v4a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-4"],
    "download": ["M12 3v12", "M7 10l5 5 5-5", "M4 15v4a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-4"],
    "bell": ["M6 9a6 6 0 0 1 12 0c0 6 2.5 8 2.5 8h-17S6 15 6 9z", "M10 20.5a2.2 2.2 0 0 0 4 0"],
    "gauge": ["M3.5 17a9 9 0 1 1 17 0", line(12, 13.5, 16, 9), dot(12, 14)],
    "part": [circle(12, 12, 3), radial_ticks(12, 12, 6, 9, 8, 22.5), circle(12, 12, 6.5)],
    "book": ["M4 4.5A2.5 2.5 0 0 1 6.5 2H20v16H6.5A2.5 2.5 0 0 0 4 20.5z", "M4 20.5A2.5 2.5 0 0 0 6.5 23H20v-5"],
    "report": ["M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z", "M14 2v6h6", line(8, 13, 16, 13), line(8, 17, 13, 17)],
    "search": [circle(11, 11, 7), line(16, 16, 21, 21)],
    "edit": ["M16.5 3.5a2.1 2.1 0 0 1 3 3L8 18l-4 1 1-4z"],
    "trash": [line(3, 6, 21, 6), "M8 6V4h8v2", "M5.5 6l1 15h11l1-15", line(10, 10.5, 10, 16.5), line(14, 10.5, 14, 16.5)],
    "check": ["M4.5 12.5l5 5 10-11"],
    "x": [line(6, 6, 18, 18), line(18, 6, 6, 18)],
    "chevron_right": ["M9 5l7 7-7 7"],
    "chevron_left": ["M15 5l-7 7 7 7"],
    "chevron_down": ["M5 9l7 7 7-7"],
    "sun": [circle(12, 12, 4), radial_ticks(12, 12, 7, 9.5, 8)],
    "moon": ["M20 14.5A8.5 8.5 0 1 1 9.5 4a7 7 0 0 0 10.5 10.5z"],
    "monitor": [rect(2, 3.5, 20, 13, 2), line(8, 21, 16, 21), line(12, 16.5, 12, 21)],
    "logout": ["M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4", "M16 17l5-5-5-5", line(21, 12, 9, 12)],
    "sync": ["M20 12a8 8 0 0 0-13.7-5.6L4 8.5", "M4 3.5v5h5", "M4 12a8 8 0 0 0 13.7 5.6L20 15.5", "M20 20.5v-5h-5"],
    "link": ["M10 14a4.5 4.5 0 0 0 6.4 0l3-3a4.5 4.5 0 0 0-6.4-6.4l-1 1", "M14 10a4.5 4.5 0 0 0-6.4 0l-3 3a4.5 4.5 0 0 0 6.4 6.4l1-1"],
    "external": ["M14 3h7v7", line(21, 3, 11, 13), "M19 14v5a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2h5"],
    "scan": ["M3 8V5a2 2 0 0 1 2-2h3", "M16 3h3a2 2 0 0 1 2 2v3", "M21 16v3a2 2 0 0 1-2 2h-3", "M8 21H5a2 2 0 0 1-2-2v-3", line(7, 12, 17, 12)],
    "barcode": ["M3 7V5a2 2 0 0 1 2-2h2", "M17 3h2a2 2 0 0 1 2 2v2", "M21 17v2a2 2 0 0 1-2 2h-2", "M7 21H5a2 2 0 0 1-2-2v-2",
                line(7, 8, 7, 16), line(10, 8, 10, 16), line(12.5, 8, 12.5, 16), line(15, 8, 15, 16), line(17, 8, 17, 16)],
    "image": [rect(3, 3, 18, 18, 2), circle(9, 9, 2), "M21 15.5l-5-5L5 21"],
    "calendar": [rect(3, 4.5, 18, 17, 2), line(3, 9.5, 21, 9.5), line(8, 2.5, 8, 6.5), line(16, 2.5, 16, 6.5)],
    "dollar": [line(12, 2, 12, 22), "M17 5.5H9.5a3.5 3.5 0 0 0 0 7h5a3.5 3.5 0 0 1 0 7H6"],
    "settings": [circle(12, 12, 3), "M12 2.5v2.5M12 19v2.5M4.6 4.6l1.8 1.8M17.6 17.6l1.8 1.8M2.5 12H5M19 12h2.5M4.6 19.4l1.8-1.8M17.6 6.4l1.8-1.8", circle(12, 12, 7)],
    "sticker": [rect(3, 3, 18, 18, 2), line(7, 8, 17, 8), line(7, 12, 14, 12), line(7, 16, 12, 16), "M15 15.5l1.5 1.5 3-3"],
    "carfax": ["M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z", "M14 2v6h6", "M8 15l2.5 2.5L16 12"],
    "list": [line(8, 6, 21, 6), line(8, 12, 21, 12), line(8, 18, 21, 18), dot(3.5, 6), dot(3.5, 12), dot(3.5, 18)],
    "spec": [rect(4, 3, 16, 18, 2), line(8, 8, 16, 8), line(8, 12, 16, 12), line(8, 16, 12, 16)],
    "info": [circle(12, 12, 9.5), line(12, 11, 12, 16.5), dot(12, 7.5)],
    "warning": ["M10.3 3.9L2 18a2 2 0 0 0 1.7 3h16.6a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z", line(12, 9, 12, 13.5), dot(12, 17)],
    "grid": [rect(3, 3, 7.5, 7.5, 2), rect(13.5, 3, 7.5, 7.5, 2), rect(3, 13.5, 7.5, 7.5, 2), rect(13.5, 13.5, 7.5, 7.5, 2)],
    "menu": [line(4, 6, 20, 6), line(4, 12, 20, 12), line(4, 18, 20, 18)],
    "wrench": TYPE_ICONS["general_repair"],
    "maintenance": [circle(12, 12, 9.5), "M12 7v5l3.5 2"],
    "repair": TYPE_ICONS["general_repair"],
    "key": [circle(7.5, 15.5, 4.5), line(10.7, 12.3, 21, 2), line(17, 6, 19.5, 8.5), line(14.5, 8.5, 16.5, 10.5)],
    "user": [circle(12, 8, 4), "M4 21a8 8 0 0 1 16 0"],
}


# ---------------------------------------------------------------------------
# Writers
# ---------------------------------------------------------------------------
def escape(text: str) -> str:
    """Make text safe inside SVG/XML ("&" -> "&amp;" etc.)."""
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


SVG_HEAD = ('<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" '
            'stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">')


def to_svg(paths: list[str], title: str) -> str:
    body = "".join(f'<path d="{p}"/>' for p in paths)
    return f"{SVG_HEAD}<title>{escape(title)}</title>{body}</svg>\n"  # escape "&" etc.


def to_vector_drawable(paths: list[str]) -> str:
    """Android VectorDrawable with the same paths (tinted by the app)."""
    items = "\n".join(
        f'    <path android:pathData="{p}" android:strokeColor="#FF000000" android:strokeWidth="2" '
        f'android:strokeLineCap="round" android:strokeLineJoin="round" android:fillColor="#00000000"/>'
        for p in paths
    )
    return ('<?xml version="1.0" encoding="utf-8"?>\n'
            '<!-- Generated by icons/build_icons.py — do not edit by hand. -->\n'
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24"\n'
            '    android:tint="?attr/colorControlNormal">\n'
            f"{items}\n</vector>\n")


def main() -> None:
    catalog = json.loads((ROOT / "catalog" / "catalog.json").read_text(encoding="utf-8"))
    labels = {t["key"]: t["label"] for t in catalog["types"]}
    missing = sorted(set(labels) - set(TYPE_ICONS))
    extra = sorted(set(TYPE_ICONS) - set(labels))
    assert not missing, f"catalog types without an icon: {missing}"
    assert not extra, f"icons for unknown types: {extra}"

    types_dir = ROOT / "icons" / "types"
    ui_dir = ROOT / "icons" / "ui"
    types_dir.mkdir(parents=True, exist_ok=True)
    ui_dir.mkdir(parents=True, exist_ok=True)
    for key, paths in TYPE_ICONS.items():
        (types_dir / f"{key}.svg").write_text(to_svg(paths, labels[key]), encoding="utf-8")
    for name, paths in UI_ICONS.items():
        (ui_dir / f"{name}.svg").write_text(to_svg(paths, name.replace("_", " ")), encoding="utf-8")

    # A single "sprite" file lets the web app show any icon with one request.
    symbols = []
    for prefix, table in (("t-", TYPE_ICONS), ("u-", UI_ICONS)):
        for key, paths in table.items():
            symbols.append(f'<symbol id="{prefix}{key}" viewBox="0 0 24 24">' + "".join(f'<path d="{p}"/>' for p in paths) + "</symbol>")
    sprite = ('<svg xmlns="http://www.w3.org/2000/svg" style="display:none">' + "".join(symbols) + "</svg>\n")
    (ROOT / "icons" / "sprite.svg").write_text(sprite, encoding="utf-8")

    drawable = ROOT / "android" / "app" / "src" / "main" / "res" / "drawable"
    if (ROOT / "android").is_dir():
        drawable.mkdir(parents=True, exist_ok=True)
        for key, paths in TYPE_ICONS.items():
            (drawable / f"ic_type_{key}.xml").write_text(to_vector_drawable(paths), encoding="utf-8")
        for name, paths in UI_ICONS.items():
            (drawable / f"ic_ui_{name}.xml").write_text(to_vector_drawable(paths), encoding="utf-8")
        # Direct references to every icon.  The app must NOT look icons up by
        # name at run time: the release build's resource shrinker can't see
        # name lookups and deleted all the icons (the app then crashed on
        # every screen that shows one).  Referencing R.drawable.* directly
        # keeps them, and the compiler checks every one exists.
        kt = ROOT / "android" / "app" / "src" / "main" / "java" / "com" / "ratchetwrench" / "app" / "ui" / "IconResources.kt"
        type_lines = "\n".join(f'    "{k}" to R.drawable.ic_type_{k},' for k in TYPE_ICONS)
        ui_lines = "\n".join(f'    "{k}" to R.drawable.ic_ui_{k},' for k in UI_ICONS)
        kt.write_text(
            "// IconResources.kt — generated by icons/build_icons.py; do not edit by hand.\n"
            "// Every icon is referenced directly so the build keeps it (see build_icons.py).\n"
            "package com.ratchetwrench.app.ui\n\nimport com.ratchetwrench.app.R\n\n"
            "/** Maintenance/repair type key -> drawable. */\n"
            f"val TYPE_ICON_RES: Map<String, Int> = mapOf(\n{type_lines}\n)\n\n"
            "/** Interface icon name -> drawable. */\n"
            f"val UI_ICON_RES: Map<String, Int> = mapOf(\n{ui_lines}\n)\n",
            encoding="utf-8",
        )
        # Second layer of protection: tell the resource shrinker to keep them.
        raw = ROOT / "android" / "app" / "src" / "main" / "res" / "raw"
        raw.mkdir(parents=True, exist_ok=True)
        (raw / "keep_icons.xml").write_text(
            '<?xml version="1.0" encoding="utf-8"?>\n'
            "<!-- Generated by icons/build_icons.py: never let the release build's resource\n"
            "     shrinker delete the icons (the app shows them by type key). -->\n"
            '<resources xmlns:tools="http://schemas.android.com/tools"\n'
            '    tools:keep="@drawable/ic_type_*,@drawable/ic_ui_*,@drawable/ic_launcher_*" />\n',
            encoding="utf-8",
        )
    print(f"wrote {len(TYPE_ICONS)} type icons and {len(UI_ICONS)} UI icons")


if __name__ == "__main__":
    main()
