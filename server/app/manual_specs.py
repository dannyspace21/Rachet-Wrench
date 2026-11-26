"""
manual_specs.py — find fluid specs and capacities in an owner's manual.

The owner's manual is the most trustworthy source for what oil, coolant,
transmission fluid and brake fluid a vehicle takes, and how much.  This
module reads the text of the manual (extracted by the sandboxed PDF worker)
and PROPOSES values: every candidate comes with the page number and the line
it was found on, and nothing is saved until the person approves it on the
review screen (exactly like the door-sticker scan).

Rules:
  * Nothing is guessed.  A value is only proposed when the manual's own text
    contains it next to the right words (e.g. "0W-20" on a line about engine
    oil).  If several different values are found (e.g. "0W-20" and "5W-30
    in an emergency"), all are offered and the person picks.
  * Pages that look like the manual's specifications / capacities section
    rank first.
  * Values are kept as the manual writes them ("5.7 L (6.0 qt)").
"""

from __future__ import annotations

import re

QTY = r"\d{1,3}(?:[.,]\d{1,2})?\s*(?:L|liters?|litres?|US\s*qt\.?|qt\.?|quarts?|gal\.?|gallons?|US\s*gal\.?|Imp\.?\s*qt\.?)\b"
QTY_RE = re.compile(QTY + r"(?:\s*\([^)]{0,40}\))?", re.IGNORECASE)
VISC_RE = re.compile(r"\b(?:SAE\s*)?(0W|5W|10W|15W|20W)\s*-?\s*(\d{2})\b", re.IGNORECASE)
OIL_STD_RE = re.compile(r"\b(API\s+S[A-Z](?:\s*(?:/|,|or)\s*S[A-Z])?|ILSAC\s+GF-?\d[A-Z]?|ACEA\s+[A-C]\d(?:/[A-C]\d)?)\b", re.IGNORECASE)
BRAKE_RE = re.compile(r"\bDOT[\s-]?(3|4|5\.1|5)\b|\bSAE\s?J1703\b|\bFMVSS\s*(?:No\.?\s*)?116\b", re.IGNORECASE)
ATF_RE = re.compile(r"(ATF\s?(?:WS|T-IV|Type\s?T-IV|SP-?[IV]{1,4}|DW-1|Z-1|\+4|Matic[\w -]{0,10})|Dexron[\w -]{0,12}|Mercon[\w -]{0,12}|CVT\s?Fluid[\w -]{0,15}|CVTF[\w -]{0,10}|ATF\s?[A-Z0-9+-]{2,8})", re.IGNORECASE)
GEAR_RE = re.compile(r"\b(\d{2}W-\d{2,3}|API\s+GL-[45]|GL-[45]|Hypoid gear oil[\w -]{0,20})\b", re.IGNORECASE)
PLUG_RE = re.compile(r"\b(?:(DENSO|NGK|Champion|Bosch|Motorcraft|AC Delco)\s+)?([A-Z]{1,6}\d{1,3}[A-Z0-9]{0,6}(?:-[A-Z0-9]{1,6})?)\b")
TORQUE_RE = re.compile(r"\b(\d{2,3})\s*(?:ft[·.\- ]?lbf?|lbf?[·.\- ]?ft|ft-lb)\b|\b(\d{2,3})\s*N[·.]?m\b", re.IGNORECASE)

SPEC_WORDS = re.compile(r"capacit|specification|fluid\s+and\s+lubricant|lubricant|maintenance\s+data", re.IGNORECASE)

# key -> (words that must appear on the line, how to pull the value out)
RULES: dict[str, tuple[re.Pattern, str]] = {
    "oil_type": (re.compile(r"engine\s+oil|oil\s+(?:grade|type|viscosity|quality)|motor\s+oil|recommended\s+oil", re.I), "oil"),
    "oil_capacity": (re.compile(r"(?:engine\s+oil|oil\s+capacity|oil\s+change)[^.]{0,60}(?:filter|capacit|drain|refill)|with\s+(?:oil\s+)?filter", re.I), "qty"),
    "coolant_type": (re.compile(r"\bcoolant\b[^.]{0,40}(?:type|use|recommend|genuine|long\s*life|antifreeze|LLC)|\bantifreeze\b", re.I), "coolant"),
    "coolant_capacity": (re.compile(r"(?:engine\s+)?coolant[^.]{0,40}(?:capacit|system)|cooling\s+system", re.I), "qty"),
    "transmission_fluid_type": (re.compile(r"(?:automatic\s+)?transmission\s+fluid|\bATF\b|\bCVT\b\s*fluid|transaxle\s+fluid", re.I), "atf"),
    "transmission_fluid_capacity": (re.compile(r"(?:automatic\s+)?transmission(?:\s+fluid)?[^.]{0,40}capacit|transaxle[^.]{0,30}capacit|\bATF\b[^.]{0,30}capacit", re.I), "qty"),
    "brake_fluid_type": (re.compile(r"brakes?(?:\s*(?:and|/)\s*clutch)?\s+fluid", re.I), "brake"),
    "differential_fluid_type": (re.compile(r"differential(?:\s+(?:gear\s+)?oil|\s+fluid)?", re.I), "gear"),
    "fuel_capacity": (re.compile(r"fuel\s+tank|fuel\s+capacit", re.I), "qty"),
    "spark_plug_part": (re.compile(r"spark\s+plug", re.I), "plug"),
    "lug_nut_torque": (re.compile(r"(?:lug|wheel)\s+nut[^.]{0,40}torque|torque[^.]{0,30}(?:lug|wheel)\s+nut", re.I), "torque"),
}

LABELS = {
    "oil_type": "Engine oil type / viscosity", "oil_capacity": "Engine oil capacity (with filter)",
    "coolant_type": "Coolant type", "coolant_capacity": "Coolant capacity",
    "transmission_fluid_type": "Transmission fluid type", "transmission_fluid_capacity": "Transmission fluid capacity",
    "brake_fluid_type": "Brake fluid type", "differential_fluid_type": "Differential fluid type",
    "fuel_capacity": "Fuel tank capacity", "spark_plug_part": "Spark plug part number", "lug_nut_torque": "Lug nut torque",
}

_PLUG_NOT = {"FOR", "TYPE", "AND", "THE", "WITH", "NGK", "DENSO", "SAE", "API", "DOT", "LF", "RF", "MPH"}


def _value(kind: str, text: str) -> str | None:
    """Pull one value of this kind out of a short piece of text (or None)."""
    if kind == "qty":
        m = QTY_RE.search(text)
        return re.sub(r"\s+", " ", m.group(0)).strip() if m else None
    if kind == "oil":
        vals = _oil_values(text)
        return vals[0] if vals else None
    if kind == "brake":
        # "DOT 3" is what's on the bottle; prefer it over the standard's name.
        ms = list(BRAKE_RE.finditer(text))
        dot = next((m for m in ms if m.group(1)), None)
        if dot:
            return f"DOT {dot.group(1)}"
        return re.sub(r"\s+", " ", ms[0].group(0)).upper() if ms else None
    if kind == "atf":
        m = ATF_RE.search(text)
        return re.sub(r"\s+", " ", m.group(1)).strip(" -") if m else None
    if kind == "gear":
        m = GEAR_RE.search(text)
        return re.sub(r"\s+", " ", m.group(1)).strip() if m else None
    if kind == "coolant":
        # The coolant's own name, e.g. "Toyota Super Long Life Coolant".
        for m in re.finditer(r"((?:[A-Z][\w®™-]*\s+){0,6}(?:Long\s*Life\s*Coolant|LLC|Coolant|Antifreeze)(?:\s*\([^)]{0,30}\))?)", text):
            v = re.sub(r"\s+", " ", m.group(1)).strip()
            if len(v.split()) >= 2 and v.lower() not in {"engine coolant", "the coolant", "coolant type"}:
                return v
        return None
    if kind == "plug":
        for m in PLUG_RE.finditer(text):
            part = m.group(2)
            if part in _PLUG_NOT or not re.search(r"\d", part) or not re.search(r"[A-Z]", part) or len(part) < 4:
                continue
            return f"{m.group(1)} {part}" if m.group(1) else part
        return None
    if kind == "torque":
        m = TORQUE_RE.search(text)
        if not m:
            return None
        return f"{m.group(1)} ft-lb" if m.group(1) else f"{m.group(2)} N·m"
    return None


def _oil_values(text: str) -> list[str]:
    """Every oil grade on the line ("0W-20", then "5W-20 in an emergency"),
    with the oil standard when it's written next to the first one."""
    out: list[str] = []
    std = OIL_STD_RE.search(text)
    for n, m in enumerate(VISC_RE.finditer(text)):
        value = f"{m.group(1).upper()}-{m.group(2)}"
        if n == 0 and std:
            value += " (" + re.sub(r"\s+", " ", std.group(1)) + ")"
        if value not in out:
            out.append(value)
    return out


def _values(kind: str, line: str, window: str) -> list[str]:
    """All values of this kind on the line (oil can list several grades);
    if the line has none, the first one in the next couple of lines."""
    if kind == "oil":
        return _oil_values(line) or _oil_values(window)[:1]
    v = _value(kind, line) or _value(kind, window)
    return [v] if v else []


def find_specs(pages: list[str]) -> list[dict]:
    """[{key, label, options: [{value, page, snippet}]}] — candidates for
    each spec found in the manual, best first.  Specs not found are absent."""
    found: dict[str, dict[str, dict]] = {}
    for page_no, text in enumerate(pages, start=1):
        if not text:
            continue
        lines = [re.sub(r"[ \t]+", " ", ln).strip() for ln in text.splitlines()]
        lines = [ln for ln in lines if ln]
        spec_page = bool(SPEC_WORDS.search(text))
        for i, line in enumerate(lines):
            window = " ".join(lines[i:i + 3])  # tables often put the value on the next line
            # A section heading on the line above ("Automatic transmission")
            # gives context to the line below ("Fluid capacity 8.4 L").
            context = (lines[i - 1] + " " + line) if i else line
            for key, (words, kind) in RULES.items():
                if not words.search(context):
                    continue
                # Matched only thanks to the heading above: take the value from
                # this line only (the next lines belong to another item).
                own = bool(words.search(line))
                for value in (_values(kind, line, window) if own else _values(kind, line, line)):
                    opts = found.setdefault(key, {})
                    o = opts.get(value.lower())
                    if o is None:
                        o = opts[value.lower()] = {"value": value, "page": page_no, "snippet": window[:220], "score": 0}
                    o["score"] += 3 if spec_page else 1
    out = []
    for key in RULES:
        if key not in found:
            continue
        options = sorted(found[key].values(), key=lambda o: (-o["score"], o["page"]))[:4]
        out.append({"key": key, "label": LABELS[key],
                    "options": [{k: o[k] for k in ("value", "page", "snippet")} for o in options]})
    return out
