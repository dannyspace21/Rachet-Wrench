"""
sticker_parser.py — turns the raw text read from door-jamb stickers into
labelled fields (VIN, tire sizes, pressures, paint code, GVWR ...).

The text comes from optical character recognition (OCR): ML Kit on the
phone, Tesseract on the server, or the optional AI vision mode.  OCR text is
messy, so every rule here is conservative: if a value isn't clearly there,
we return nothing for that field rather than guess.  The user then confirms,
edits or rejects every field on a review screen before anything is saved.

The Android app contains a Kotlin copy of these exact rules
(StickerParser.kt); both are tested against the same sample texts.
"""

from __future__ import annotations

import re

from .vin import is_valid_vin, normalize_vin

# A modern tire size, e.g. "235/40R19 96W", "P225/45ZR17", "120/70ZR17 M/C (58W)".
TIRE_RE = re.compile(
    r"\b(?:P|LT|T)?(\d{3})\s?/\s?(\d{2})\s?(Z?R|-)\s?(\d{2}(?:\.\d)?)(?:\s*(?:M/C)?\s*\(?(\d{2,3}[A-Z])\)?)?",
    re.IGNORECASE,
)
# A compact-spare size like "T135/80D18" or "T125/70D17".
SPARE_RE = re.compile(r"\bT(\d{3})\s?/\s?(\d{2})\s?(D|R)\s?(\d{2})\b", re.IGNORECASE)
# Pressures: "240 kPa, 35 PSI" / "35PSI" / "2.5 bar".
PSI_RE = re.compile(r"(\d{2,3})\s*(?:PSI|LBS?\.?/?\s*IN)", re.IGNORECASE)
KPA_RE = re.compile(r"(\d{3})\s*KPA", re.IGNORECASE)
# Weights: "GVWR 4,520 LB" / "GVWR: 2050 KG (4520 LB)".
LB_RE = r"([\d][\d,\.]{2,6})\s*(?:LB|LBS)"
KG_RE = r"([\d][\d,\.]{2,6})\s*KG"
DATE_RE = re.compile(r"\b(?:MFD|MFG|DATE|MANUFACTURED)[^0-9A-Z]{0,12}(?:BY\s*[^0-9]{0,40})?(\d{1,2})\s*/\s*(\d{2,4})\b", re.IGNORECASE)
DATE_FALLBACK_RE = re.compile(r"\b(0[1-9]|1[0-2])\s*/\s*(\d{2}|20\d{2})\b")


def _clean_lines(text: str) -> list[str]:
    """Split OCR text into trimmed, non-empty lines."""
    return [re.sub(r"[ \t]+", " ", line).strip() for line in text.splitlines() if line.strip()]


def _num(raw: str) -> str:
    """'4,520' -> '4520'."""
    return raw.replace(",", "").replace(" ", "").rstrip(".")


# GVWR / GAWR labels as printed on certification labels (the one with the
# VIN, often on the driver's door pillar), incl. dotted and French/Canadian
# bilingual forms.  Same rules in Android's StickerParser (shared cases in
# catalog/sticker_weight_tests.json).
WEIGHT_LABEL_RE = re.compile(
    r"\bG\.?\s?V\.?\s?W\.?\s?R\b\.?(?:\s*/\s*PNBV)?"
    r"|\bG\.?\s?A\.?\s?W\.?\s?R\b\.?(?:\s*/\s*PNBE)?\s*[:\-]?\s*(FRONT|FRT|FR|F|REAR|RR|R)\b(?:\s*/?\s*(?:PNBE\s*)?(?:AV|AR)\b)?"
    r"|\b(FRONT|REAR)\b(?:\s*/\s*(?:AV|AR)\b)?")
W_KG_LB_RE = re.compile(r"(\d[\d,\.]{2,6})\s*KGS?[^0-9]{0,6}(\d[\d,\.]{2,6})\s*LBS?\b")
W_LB_RE = re.compile(r"(\d[\d,\.]{2,6})\s*LBS?\b")
W_KG_RE = re.compile(r"(\d[\d,\.]{2,6})\s*KGS?\b")


def _kg_to_lb(kg: str) -> str:
    return str(round(float(_num(kg)) * 2.20462))


def _weight(seg: str) -> str | None:
    """The weight in lb in a piece of text ("2050 KG (4520 LB)" -> "4520")."""
    m = W_KG_LB_RE.search(seg)
    if m:
        return _num(m.group(2))
    m = W_LB_RE.search(seg)
    if m:
        return _num(m.group(1))
    m = W_KG_RE.search(seg)
    return _kg_to_lb(m.group(1)) if m else None


def _weight_key(m: re.Match, gawr_seen: bool) -> str | None:
    """Which weight a label is: GVWR (no side), or GAWR front / rear."""
    side = m.group(1) or m.group(2)
    if side is None:
        return "gvwr"
    if m.group(2) and not gawr_seen:
        return None
    return "gawr_front" if side.startswith("F") else "gawr_rear"


def parse_weights(upper: str) -> dict[str, str]:
    """GVWR and front/rear GAWR in lb from upper-case sticker text."""
    out: dict[str, str] = {}
    lines = [ln for ln in upper.splitlines() if ln.strip()]
    for i, line in enumerate(lines):
        found = []
        gawr_seen = False
        for m in WEIGHT_LABEL_RE.finditer(line):
            # A bare FRONT/REAR only counts after a GAWR label (not "FRONT 35 PSI").
            key = _weight_key(m, gawr_seen)
            if m.group(1):
                gawr_seen = True
            if key:
                found.append((key, m))
        if not found:
            continue
        missing = []
        for n, (key, m) in enumerate(found):
            seg = line[m.end(): found[n + 1][1].start() if n + 1 < len(found) else len(line)]
            w = _weight(seg)
            if w and key not in out:
                out[key] = w
            elif not w:
                missing.append(key)
        if missing:
            # Table layout: headings on this line, values (kg and/or lb) below.
            below = " ".join(lines[i + 1:i + 4])
            lbs = [_num(x) for x in W_LB_RE.findall(below)]
            kgs = [_kg_to_lb(x) for x in W_KG_RE.findall(below)]
            vals = lbs if len(lbs) >= len(missing) else kgs if len(kgs) >= len(missing) else []
            for key, v in zip(missing, vals):
                out.setdefault(key, v)
    return out


def find_vin(text: str) -> str | None:
    """Find a 17-character VIN with a correct check digit.  OCR often reads
    letters as digits and vice-versa; normalize_vin() fixes the common ones
    (O->0, I->1, Q->0) because those letters never appear in VINs."""
    lines = text.upper().splitlines()
    # 1) Best: text that follows a "VIN" label (spaces/dashes inside allowed).
    for line in lines:
        label = re.search(r"\bV\.?I\.?N\b\.?", line)
        if not label:
            continue
        squeezed = re.sub(r"[^A-Z0-9]", "", line[label.end():])
        for i in range(0, max(0, len(squeezed) - 16)):
            cand = normalize_vin(squeezed[i : i + 17])
            if is_valid_vin(cand):
                return cand
    # 2) Otherwise: a single 17-character word with enough digits.  (We never
    #    glue separate words together: that can fake a valid check digit.)
    for line in lines:
        for word in re.split(r"[\s,;:]+", line):
            cand = normalize_vin(word.strip("-*#."))
            if len(cand) == 17 and sum(c.isdigit() for c in cand) >= 5 and is_valid_vin(cand):
                return cand
    return None


def _tire_string(m: re.Match) -> str:
    """Rebuild a clean tire size from regex groups, e.g. '235/40R19 96W'."""
    width, aspect, construction, rim, load = m.group(1), m.group(2), m.group(3).upper(), m.group(4), m.group(5)
    construction = "R" if construction == "-" else construction
    size = f"{width}/{aspect}{construction}{rim}"
    return f"{size} {load.upper()}" if load else size


def parse_sticker_text(text: str) -> dict[str, str]:
    """Return {spec_key: value} for every field we could recognize."""
    fields: dict[str, str] = {}
    if not text or not text.strip():
        return fields
    lines = _clean_lines(text)
    upper = "\n".join(lines).upper()

    vin = find_vin(upper)
    if vin:
        fields["vin"] = vin

    # --- Tires: spare first so it isn't mistaken for a road tire ----------
    spare = SPARE_RE.search(upper)
    if spare:
        fields["tire_size_spare"] = f"T{spare.group(1)}/{spare.group(2)}{spare.group(3).upper()}{spare.group(4)}"
    spare_span = spare.span() if spare else (-1, -1)

    tires = [m for m in TIRE_RE.finditer(upper) if not (spare_span[0] <= m.start() < spare_span[1] + 1)]
    # Look for explicit FRONT / REAR labels on the same line as a size.
    for line in upper.splitlines():
        m = TIRE_RE.search(line)
        if not m:
            continue
        if re.search(r"\bFRONT\b|\bFRT\b|\bF\b\s*:", line) and "tire_size_front" not in fields:
            fields["tire_size_front"] = _tire_string(m)
        elif re.search(r"\bREAR\b|\bRR\b|\bR\b\s*:", line) and "tire_size_rear" not in fields:
            fields["tire_size_rear"] = _tire_string(m)
    if tires and "tire_size_front" not in fields:
        fields["tire_size_front"] = _tire_string(tires[0])
    if tires and "tire_size_rear" not in fields:
        # Staggered setups print two different sizes: the 2nd is the rear.
        distinct = []
        for m in tires:
            s = _tire_string(m)
            if s not in distinct:
                distinct.append(s)
        fields["tire_size_rear"] = distinct[1] if len(distinct) > 1 else distinct[0]

    for key in ("front", "rear"):
        size = fields.get(f"tire_size_{key}")
        if size:
            rim = re.search(r"R(\d{2}(?:\.\d)?)", size)
            if rim:
                fields[f"rim_size_{key}"] = rim.group(1)

    # --- Pressures --------------------------------------------------------
    # Prefer PSI values on lines labelled FRONT / REAR / SPARE.
    for line in upper.splitlines():
        psi = PSI_RE.search(line)
        if not psi:
            continue
        value = psi.group(1)
        if not 10 <= int(value) <= 80:
            continue
        if "SPARE" in line and "tire_pressure_spare" not in fields:
            fields["tire_pressure_spare"] = value
        elif re.search(r"\bFRONT\b|\bFRT\b", line) and "tire_pressure_front" not in fields:
            fields["tire_pressure_front"] = value
        elif re.search(r"\bREAR\b|\bRR\b", line) and "tire_pressure_rear" not in fields:
            fields["tire_pressure_rear"] = value
    # Unlabelled: on US tire placards the PSI values appear in the order
    # front, rear, spare.
    all_psi = [m.group(1) for m in PSI_RE.finditer(upper) if 10 <= int(m.group(1)) <= 80]
    order = ["tire_pressure_front", "tire_pressure_rear", "tire_pressure_spare"]
    if all_psi and not any(k in fields for k in order):
        for k, v in zip(order, all_psi, strict=False):
            if k == "tire_pressure_spare" and "tire_size_spare" not in fields:
                break
            fields[k] = v
    elif all_psi:
        remaining = [v for v in all_psi]
        for k in order:
            if k in fields and fields[k] in remaining:
                remaining.remove(fields[k])
        for k in order:
            if k not in fields and remaining and (k != "tire_pressure_spare" or "tire_size_spare" in fields):
                fields[k] = remaining.pop(0)

    # --- Weights ----------------------------------------------------------
    fields.update(parse_weights(upper))

    # --- Paint & trim -----------------------------------------------------
    m = re.search(r"\b(?:PAINT|COLOU?R|C/TR|PNT)\b(?:\s*CODE)?\s*[:#]?\s*([A-Z0-9]{2,4})\b", upper)
    if m and m.group(1) not in {"CODE", "TRIM"}:
        fields["paint_code"] = m.group(1)
    # Toyota/Lexus style "C/TR: 223/LA20" -> paint 223, trim LA20.
    m = re.search(r"C/TR\s*[:]?\s*([A-Z0-9]{2,4})\s*/\s*([A-Z0-9]{2,6})", upper)
    if m:
        fields["paint_code"] = m.group(1)
        fields["trim_code"] = m.group(2)
    else:
        m = re.search(r"\bTRIM\b(?:\s*CODE)?\s*[:#]?\s*([A-Z0-9]{2,6})\b", upper)
        if m:
            fields["trim_code"] = m.group(1)

    # --- Build date -------------------------------------------------------
    m = DATE_RE.search(upper)
    if not m:
        m = DATE_FALLBACK_RE.search(upper)
    if m:
        month, year = int(m.group(1)), m.group(2)
        if 1 <= month <= 12:
            year = year[-2:]
            fields["build_date"] = f"{month:02d}/{year}"

    return fields
