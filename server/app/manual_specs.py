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

INCH_RE = re.compile(r"(\d{1,2}(?:\.\d)?)\s*(?:in\.?(?![a-z])|inch(?:es)?\b|\")", re.IGNORECASE)
MM_RE = re.compile(r"(\d{3,4})\s*mm\b", re.IGNORECASE)
PSI_RE = re.compile(r"(\d{2})\s*psi\b", re.IGNORECASE)
KPA_RE = re.compile(r"(\d{3})\s*kPa\b", re.IGNORECASE)
BATTERY_RE = re.compile(r"\bgroup\s*(?:size\s*)?(?:no\.?\s*)?[:#]?\s*(\d{2,3}[A-Z]{0,2}|H\d|LN\d)\b|\b(\d{2,3}[A-H]\d{2}[LR]?(?:\(S\))?)\b|\b(LN\d|H\d{1,2}|T\d[A-Z]?)\b(?=[^a-z]*(?:AGM|battery|$))", re.IGNORECASE)
PART_NO_RE = re.compile(r"\b([A-Z0-9]{3,6}-[A-Z0-9]{3,6}(?:-[A-Z0-9]{1,5})?)\b")
RIM_RE = re.compile(r"\b(\d{2}(?:\.\d)?\s*[x×]\s*\d{1,2}(?:\.\d)?\s*J{1,2})\b", re.IGNORECASE)
OCTANE_RE = re.compile(r"(?:octane(?:\s+number|\s+rating)?[^0-9]{0,25}(\d{2})|(\d{2})\s*(?:octane|AKI|RON|\(R\s*\+\s*M\)\s*/\s*2))", re.IGNORECASE)
CHAIN_RE = re.compile(r"\b(\d{3})(?:\s*[A-Z]{0,4}\d?)?\s*(?:[x×]\s*(\d{2,3})|[^0-9]{0,20}?(\d{2,3})\s*links)", re.IGNORECASE)
TEETH_RE = re.compile(r"(\d{2})\s*(?:T\b|teeth)", re.IGNORECASE)
SLACK_RE = re.compile(r"(\d{1,2}(?:\.\d)?\s*(?:[–-]|to)\s*\d{1,2}(?:\.\d)?\s*mm|\d{1,2}(?:\.\d)?\s*mm)", re.IGNORECASE)
FORK_RE = re.compile(r"\b(\d{1,2}W(?:-\d{2})?|G\s?\d{1,2}|SS-\d{1,2}|#\s?\d{1,2})\b", re.IGNORECASE)
SEATS_RE = re.compile(r"(\d{1,2})\s*(?:persons?|people|passengers?|occupants|seats?)\b|\bcapacity[^0-9]{0,15}(\d{1,2})\b", re.IGNORECASE)

# Words that say which side / position a value is for ("Front 33 psi  Rear 35 psi").
SIDE_RE = re.compile(r"\b(front|rear|driver(?:'?s)?(?:\s+side)?|passenger(?:'?s)?(?:\s+side)?|spare|temporary|compact|left|right)\b", re.IGNORECASE)
SIDE_OF = {"front": "front", "rear": "rear", "driver": "driver", "left": "driver", "passenger": "passenger", "right": "passenger",
           "spare": "spare", "temporary": "spare", "compact": "spare"}

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
    "oil_filter_part": (re.compile(r"oil\s+filter[^.]{0,30}(?:part|no\.|number|genuine)|oil\s+filter\s*[:#]", re.I), "partno"),
    "power_steering_fluid_type": (re.compile(r"power\s+steering\s+fluid", re.I), "atf"),
    "fork_oil_type": (re.compile(r"fork\s+oil", re.I), "fork"),
    "fuel_type": (re.compile(r"\boctane\b|\bAKI\b|\(R\s*\+\s*M\)\s*/\s*2", re.I), "octane"),
    "tire_size_front": (re.compile(r"\btires?\b|\btyres?\b", re.I), "tire@front"),
    "tire_size_rear": (re.compile(r"\btires?\b|\btyres?\b", re.I), "tire@rear"),
    "tire_size_spare": (re.compile(r"spare|temporary\s+(?:use\s+)?tire|compact\s+spare", re.I), "spare_tire"),
    "tire_pressure_front": (re.compile(r"(?:tire|tyre|inflation)\s+(?:inflation\s+)?pressure|cold\s+tire", re.I), "psi@front"),
    "tire_pressure_rear": (re.compile(r"(?:tire|tyre|inflation)\s+(?:inflation\s+)?pressure|cold\s+tire", re.I), "psi@rear"),
    "tire_pressure_spare": (re.compile(r"(?:tire|tyre|inflation)\s+(?:inflation\s+)?pressure|cold\s+tire", re.I), "psi@spare"),
    "rim_size_front": (re.compile(r"\bwheel\s+size|\brim\s+size|\bwheel\b[^.]{0,20}\b\d{2}\s*[x×]", re.I), "rim@front"),
    "rim_size_rear": (re.compile(r"\bwheel\s+size|\brim\s+size|\bwheel\b[^.]{0,20}\b\d{2}\s*[x×]", re.I), "rim@rear"),
    "battery_group": (re.compile(r"\bbattery\b", re.I), "battery"),
    "wiper_size_driver": (re.compile(r"\bwiper", re.I), "inch@driver"),
    "wiper_size_passenger": (re.compile(r"\bwiper", re.I), "inch@passenger"),
    "seating_capacity": (re.compile(r"seating\s+capacity|number\s+of\s+(?:seats|occupants|passengers)", re.I), "seats"),
    "curb_weight": (re.compile(r"curb\s+weight|kerb\s+weight", re.I), "lb"),
    "wheelbase": (re.compile(r"wheel\s*base", re.I), "length"),
    "chain_size": (re.compile(r"drive\s+chain|\bchain\b[^.]{0,20}(?:size|type|links)", re.I), "chain"),
    "sprocket_front": (re.compile(r"sprocket|gear\s+ratio", re.I), "teeth@front"),
    "sprocket_rear": (re.compile(r"sprocket|gear\s+ratio", re.I), "teeth@rear"),
    "chain_slack": (re.compile(r"chain\s+(?:slack|free\s*play|play|deflection)", re.I), "slack"),
    "gvwr": (re.compile(r"gross\s+vehicle\s+weight(?:\s+rating)?|\bG\.?V\.?W\.?R\b", re.I), "weight"),
    "gawr_front": (re.compile(r"(?:gross\s+axle\s+weight(?:\s+rating)?|\bG\.?A\.?W\.?R\b)[^.]{0,25}\bfront\b|\bfront\b[^.]{0,15}(?:gross\s+axle\s+weight|\bGAWR\b)", re.I), "weight"),
    "gawr_rear": (re.compile(r"(?:gross\s+axle\s+weight(?:\s+rating)?|\bG\.?A\.?W\.?R\b)[^.]{0,25}\brear\b|\brear\b[^.]{0,15}(?:gross\s+axle\s+weight|\bGAWR\b)", re.I), "weight"),
}

LABELS = {
    "oil_type": "Engine oil type / viscosity", "oil_capacity": "Engine oil capacity (with filter)",
    "coolant_type": "Coolant type", "coolant_capacity": "Coolant capacity",
    "transmission_fluid_type": "Transmission fluid type", "transmission_fluid_capacity": "Transmission fluid capacity",
    "brake_fluid_type": "Brake fluid type", "differential_fluid_type": "Differential fluid type",
    "fuel_capacity": "Fuel tank capacity", "spark_plug_part": "Spark plug part number", "lug_nut_torque": "Lug nut torque",
    "gvwr": "GVWR (lb)", "gawr_front": "GAWR front (lb)", "gawr_rear": "GAWR rear (lb)",
    "oil_filter_part": "Oil filter part number", "power_steering_fluid_type": "Power steering fluid type", "fork_oil_type": "Fork oil type",
    "fuel_type": "Fuel (octane)", "tire_size_front": "Tire size — front", "tire_size_rear": "Tire size — rear", "tire_size_spare": "Tire size — spare",
    "tire_pressure_front": "Tire pressure — front", "tire_pressure_rear": "Tire pressure — rear", "tire_pressure_spare": "Tire pressure — spare",
    "rim_size_front": "Wheel size — front", "rim_size_rear": "Wheel size — rear", "battery_group": "Battery group size",
    "wiper_size_driver": "Wiper size — driver", "wiper_size_passenger": "Wiper size — passenger", "seating_capacity": "Seating capacity",
    "curb_weight": "Curb weight", "wheelbase": "Wheelbase", "chain_size": "Drive chain size", "sprocket_front": "Front sprocket teeth",
    "sprocket_rear": "Rear sprocket teeth", "chain_slack": "Chain slack",
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
    if kind == "tire":
        from .sticker_parser import TIRE_RE, _tire_string
        m = TIRE_RE.search(text)
        return _tire_string(m) if m and not m.group(0).upper().startswith("T") else None
    if kind == "spare_tire":
        from .sticker_parser import SPARE_RE, TIRE_RE, _tire_string
        m = SPARE_RE.search(text)
        if m:
            return f"T{m.group(1)}/{m.group(2)}{m.group(3).upper()}{m.group(4)}"
        m = TIRE_RE.search(text)
        return _tire_string(m) if m else None
    if kind == "psi":
        m = PSI_RE.search(text)
        if m:
            return f"{m.group(1)} psi"
        m = KPA_RE.search(text)
        return f"{m.group(1)} kPa" if m else None
    if kind == "inch":
        m = INCH_RE.search(text)
        if m:
            return f"{m.group(1)} in"
        m = MM_RE.search(text)
        return f"{m.group(1)} mm" if m else None
    if kind == "length":
        m = INCH_RE.search(text)
        if m and float(m.group(1)) > 20:
            return f"{m.group(1)} in"
        m = re.search(r"(\d{2,3}(?:\.\d)?)\s*(?:in\.?(?![a-z])|inch(?:es)?\b|\")", text, re.I)
        if m:
            return f"{m.group(1)} in"
        m = MM_RE.search(text)
        return f"{m.group(1)} mm" if m else None
    if kind == "rim":
        m = RIM_RE.search(text)
        return re.sub(r"\s+", "", m.group(1)).replace("×", "x").upper().replace("X", " x ") if m else None
    if kind == "battery":
        m = BATTERY_RE.search(text)
        if not m:
            return None
        v = next(g for g in m.groups() if g)
        return f"Group {v.upper()}" if m.group(1) else v.upper()
    if kind == "partno":
        for m in PART_NO_RE.finditer(text.upper()):
            if re.search(r"\d", m.group(1)) and not re.fullmatch(r"\d{1,2}W-\d{2}", m.group(1)):
                return m.group(1)
        return None
    if kind == "fork":
        m = FORK_RE.search(text)
        return re.sub(r"\s+", "", m.group(1)).upper() if m else None
    if kind == "octane":
        m = OCTANE_RE.search(text)
        if not m:
            return None
        n = m.group(1) or m.group(2)
        return f"{n} octane" if 80 <= int(n) <= 100 else None
    if kind == "seats":
        m = SEATS_RE.search(text)
        n = (m.group(1) or m.group(2)) if m else None
        return n if n and 1 <= int(n) <= 15 else None
    if kind == "chain":
        m = CHAIN_RE.search(text)
        return f"{m.group(1)} x {m.group(2) or m.group(3)}" if m else None
    if kind == "teeth":
        m = TEETH_RE.search(text)
        return m.group(1) if m else None
    if kind == "slack":
        m = SLACK_RE.search(text)
        return re.sub(r"\s+", " ", m.group(1)) if m else None
    if kind == "lb":
        v = _value("weight", text)
        return f"{v} lb" if v else None
    if kind == "weight":
        # In lb, like the door sticker ("5,000 lb (2,268 kg)" -> "5000").
        from .sticker_parser import _weight
        return _weight(text.upper())
    if kind == "torque":
        ms = list(TORQUE_RE.finditer(text))
        ft = next((m for m in ms if m.group(1)), None)  # ft-lb first (the app's unit), N·m if that's all there is
        if ft:
            return f"{ft.group(1)} ft-lb"
        return f"{ms[0].group(2)} N·m" if ms else None
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


def _side_segment(text: str, side: str) -> str | None:
    """The part of the text that belongs to one side: from the side word to
    the next side word ("Front 33 psi  Rear 35 psi" -> rear: "Rear 35 psi").
    None if the text names other sides but not this one; the whole text if
    it names no side at all."""
    hits = list(SIDE_RE.finditer(text))
    if not hits:
        return text
    for n, m in enumerate(hits):
        if SIDE_OF[m.group(1).lower().split("'")[0].split()[0]] == side:
            end = hits[n + 1].start() if n + 1 < len(hits) else len(text)
            seg = text[m.end():end]
            # "Front and rear 35 psi": the value comes after the second word.
            if n + 1 < len(hits) and not seg.strip(" ,/&:").lower() in ("", "and"):
                return seg
            return text[m.end():]
    return None


def _values(kind: str, line: str, window: str) -> list[str]:
    """All values of this kind on the line (oil can list several grades);
    if the line has none, the first one in the next couple of lines."""
    if "@" in kind:
        base, side = kind.split("@")
        found = []
        for text in (line, window):
            seg = _side_segment(text, side)
            if seg is None:
                continue
            if seg is text and side in ("driver", "passenger"):
                continue  # no side named: can't tell driver from passenger
            if seg is text and SIDE_RE.search(text) is None and side == "spare":
                continue
            if seg is text and "spare" in text.lower() and side in ("front", "rear"):
                continue
            v = _value(base, seg)
            if v:
                found.append(v)
                break
        return found
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
                # A heading only carries over to this line if it had no value
                # of its own ("Fork oil: … (10W)" doesn't apply to the next line).
                if not own and _value(kind.split("@")[0], lines[i - 1]):
                    continue
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
