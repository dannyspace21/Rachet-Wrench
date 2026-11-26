"""
carfax.py — pulls service records out of a Carfax PDF.

Two kinds of Carfax PDF are understood:

1. "Car Care" service history (carfax.com → My Garage → Service History,
   printed to PDF).  The page has TWO columns: a short summary list on the
   left (date, odometer, shop) and one detailed card per visit on the right:

        Hogan & Sons Tire & Auto (https://www.carfax.com/Reviews-...)
        Date                Odometer
        06/01/2026          175,000 mi
        Services Performed
        Vehicle serviced
        Maintenance inspection completed
        Alignment performed                        Edit Record
        Upload Receipts / View Receipt(s) / Add Note / Edit Note

   Plain text extraction mixes the two columns together, so this format is
   read from the text WITH its position on the page (pdf_worker "layout"
   mode), keeping only the right-hand cards.

2. The classic "Vehicle History Report": rows of Date | Mileage | Source,
   followed by "- comment" lines.

Each visit's service lines are then turned into entries:

  * one entry per KIND of work, each with its own type (and so its own icon):
    "Oil and filter changed" -> Oil change, "Tires rotated" -> Tire rotation;
  * lines meaning the same thing in one visit are merged
    ("Front brake pads replaced" + "Rear brake pads replaced" -> Brake pads);
  * routine checks ("Brakes checked", "Tire condition and pressure checked",
    "Maintenance inspection completed") become ONE "Inspection" entry;
  * "Vehicle serviced" is dropped when the visit lists the actual work;
  * a line we don't recognise keeps Carfax's own words as its title, so the
    history never just says "Custom / other";
  * "Self-Service (DIY)" visits are marked as DIY (no shop name);
  * paperwork ("Title issued", "New owner reported") is shown but NOT
    pre-selected.

The result is only a PROPOSAL.  The user reviews every row before anything
is saved.  Costs are never invented (Carfax doesn't list them).
"""

from __future__ import annotations

import re
from datetime import date

DATE_AT_START = re.compile(r"^\s*(\d{1,2})/(\d{1,2})/(\d{4})\b\s*(.*)$")
MILEAGE = re.compile(r"\b(\d{1,3}(?:,\d{3})+|\d{1,7})\s*(?:mi\b|miles\b|km\b)?", re.IGNORECASE)
DATE_ONLY = re.compile(r"^(\d{1,2})/(\d{1,2})/(\d{4})$")
ODOMETER_ONLY = re.compile(r"^(\d{1,3}(?:,\d{3})+|\d{1,7})\s*(mi|km)$", re.IGNORECASE)
URL_IN_NAME = re.compile(r"\s*\(https?://[^)]*\)?", re.IGNORECASE)

# ---------------------------------------------------------------------------
# What each Carfax service line means.  (keyword, type_key, role); first
# match wins, so specific phrases come before generic ones.  Roles:
#   work  — real work: becomes an entry of that type
#   check — a routine check: folded into one "Inspection" entry per visit
#   plan  — scheduled/recommended service ("30,000 mile service performed")
#   admin — paperwork, not service (shown, not pre-selected)
#   vague — "Vehicle serviced": only kept if nothing more specific is listed
# ---------------------------------------------------------------------------
SERVICE_RULES: list[tuple[str, str, str]] = [
    ("vehicle serviced", "scheduled_service", "vague"),
    ("mile service", "scheduled_service", "plan"),
    ("recommended maintenance", "scheduled_service", "plan"),
    ("scheduled maintenance", "scheduled_service", "plan"),
    ("factory scheduled", "scheduled_service", "plan"),
    ("title issued", "registration", "admin"), ("new owner", "registration", "admin"),
    ("registration", "registration", "admin"), ("registered", "registration", "admin"),
    ("offered for sale", "registration", "admin"), ("odometer reading reported", "registration", "admin"),
    # routine checks
    ("alignment checked", "inspection", "check"), ("brakes checked", "inspection", "check"),
    ("brake checked", "inspection", "check"), ("tire condition", "inspection", "check"),
    ("pressure checked", "inspection", "check"), ("fluids checked", "inspection", "check"),
    ("battery checked", "inspection", "check"), ("battery tested", "inspection", "check"),
    ("inspection completed", "inspection", "check"), ("multi-point", "inspection", "check"),
    ("checked", "inspection", "check"),
    # engine & filters
    ("cabin air filter", "cabin_filter", "work"), ("cabin filter", "cabin_filter", "work"),
    ("oil and filter", "oil_change", "work"), ("oil & filter", "oil_change", "work"), ("oil/filter", "oil_change", "work"),
    ("oil change", "oil_change", "work"), ("oil changed", "oil_change", "work"), ("changed oil", "oil_change", "work"),
    ("lube, oil", "oil_change", "work"),
    ("oil filter", "oil_filter", "work"),
    ("air filter", "air_filter", "work"), ("fuel filter", "fuel_filter", "work"),
    ("spark plug", "spark_plugs", "work"), ("ignition coil", "ignition_coils", "work"),
    ("serpentine", "serpentine_belt", "work"), ("drive belt", "serpentine_belt", "work"),
    ("timing belt", "timing_belt", "work"), ("timing chain", "timing_chain", "work"),
    ("throttle body", "throttle_body", "work"), ("fuel system", "fuel_system_cleaning", "work"),
    ("fuel injection", "fuel_system_cleaning", "work"), ("pcv", "pcv_valve", "work"),
    ("engine mount", "engine_mounts", "work"), ("valve", "valve_adjustment", "work"),
    # cooling & fluids
    ("coolant", "coolant", "work"), ("antifreeze", "coolant", "work"), ("radiator", "radiator", "work"),
    ("water pump", "water_pump", "work"), ("thermostat", "thermostat", "work"), ("hose", "hoses", "work"),
    ("transmission", "transmission_fluid", "work"), ("brake fluid", "brake_fluid", "work"),
    ("power steering", "power_steering_fluid", "work"), ("differential", "differential_fluid", "work"),
    ("transfer case", "transfer_case_fluid", "work"), ("washer fluid", "washer_fluid", "work"),
    # tires & wheels
    ("tires rotated", "tire_rotation", "work"), ("tire rotation", "tire_rotation", "work"),
    ("rotated", "tire_rotation", "work"),
    ("alignment", "wheel_alignment", "work"),
    ("balanced", "wheel_balance", "work"), ("balance", "wheel_balance", "work"),
    ("wheel(s) repaired", "wheel_repair", "work"), ("wheel repaired", "wheel_repair", "work"),
    ("wheels repaired", "wheel_repair", "work"), ("wheel repair", "wheel_repair", "work"),
    (" rim", "wheel_repair", "work"), ("refinish", "wheel_repair", "work"),
    ("tpms", "tpms_sensor", "work"), ("tire pressure monitor", "tpms_sensor", "work"),
    ("flat", "tire_repair", "work"), ("puncture", "tire_repair", "work"), ("tire repaired", "tire_repair", "work"),
    ("tire(s) repaired", "tire_repair", "work"), ("patched", "tire_repair", "work"),
    ("mounted", "tire_replacement", "work"), ("tire(s) replaced", "tire_replacement", "work"),
    ("tires replaced", "tire_replacement", "work"), ("tire replaced", "tire_replacement", "work"),
    ("new tire", "tire_replacement", "work"),
    ("wheel bearing", "wheel_bearings", "work"),
    # brakes
    ("rotor", "brake_rotors", "work"), ("caliper", "brake_calipers", "work"), ("brake line", "brake_lines", "work"),
    ("brake pad", "brake_pads", "work"), ("brakes replaced", "brake_pads", "work"), ("brake", "brake_pads", "work"),
    # suspension & steering
    ("control arm", "suspension", "work"), ("bushing", "suspension", "work"), ("ball joint", "suspension", "work"),
    ("sway bar", "suspension", "work"), ("stabilizer", "suspension", "work"), ("spring", "suspension", "work"),
    ("suspension", "suspension", "work"),
    ("shock", "shocks_struts", "work"), ("strut", "shocks_struts", "work"),
    ("tie rod", "steering", "work"), ("steering", "steering", "work"),
    ("cv ", "cv_axles", "work"), ("axle", "cv_axles", "work"),
    # electrical
    ("battery", "battery", "work"), ("alternator", "alternator", "work"), ("starter", "starter", "work"),
    ("bulb", "lights_bulbs", "work"), ("headlight", "lights_bulbs", "work"), (" light", "lights_bulbs", "work"),
    ("fuse", "fuses", "work"), ("check engine", "diagnostics", "work"), ("diagnos", "diagnostics", "work"),
    # other systems
    ("clutch", "clutch", "work"), ("exhaust", "exhaust", "work"), ("muffler", "exhaust", "work"),
    ("catalytic", "catalytic_converter", "work"), ("a/c", "ac_service", "work"), ("air conditioning", "ac_service", "work"),
    ("wiper", "wipers", "work"), ("windshield", "glass", "work"), ("glass", "glass", "work"),
    ("washed", "detailing", "work"), ("detail", "detailing", "work"), ("wash", "detailing", "work"),
    (" key", "locks_keys", "work"), (" lock", "locks_keys", "work"),
    ("paint", "body_paint", "work"), ("body", "body_paint", "work"), (" dent", "body_paint", "work"),
    ("accident", "accident_damage", "work"), ("damage", "accident_damage", "work"), ("collision", "accident_damage", "work"),
    ("recall", "recall", "work"), ("campaign", "recall", "work"),
    ("emissions", "emissions", "work"), ("smog", "emissions", "work"),
    ("inspection", "inspection", "work"), ("inspected", "inspection", "work"),
    # motorcycle
    ("chain", "chain_adjustment", "work"), ("sprocket", "sprockets", "work"), ("fork", "fork_oil", "work"),
]

# Types that are repairs rather than routine maintenance.
REPAIR_TYPES = {
    "wheel_repair", "tire_repair", "suspension", "shocks_struts", "steering", "cv_axles", "wheel_bearings",
    "accident_damage", "body_paint", "glass", "general_repair", "recall", "diagnostics", "alternator", "starter",
    "water_pump", "radiator", "thermostat", "hoses", "exhaust", "catalytic_converter", "clutch", "brake_calipers",
    "brake_lines", "engine_mounts", "ignition_coils", "timing_chain", "fuses", "locks_keys",
}
REPAIR_WORDS = ("repaired", "repair", "leak", "failed", "broken", "damage")


RANK = {"vague": 0, "admin": 1, "check": 2, "plan": 3, "work": 4}


def classify(line: str) -> tuple[str, str]:
    """(type_key, role) for one Carfax service line."""
    low = " " + line.lower() + " "
    for keyword, key, role in SERVICE_RULES:
        if keyword in low:
            return key, role
    return "custom", "work"


def _kind(type_key: str, lines: list[str]) -> str:
    if type_key in REPAIR_TYPES:
        return "repair"
    if type_key == "custom" and any(w in " ".join(lines).lower() for w in REPAIR_WORDS):
        return "repair"
    return "maintenance"


def clean_shop(name: str) -> tuple[str, str]:
    """(performed_by, shop_name) from Carfax's source name."""
    name = re.sub(r"\s{2,}", " ", URL_IN_NAME.sub("", name)).strip(" -·")
    if not name or re.match(r"(?i)^self[- ]?service|^diy\b|^owner", name):
        return "diy", ""
    return "shop", name[:120]


def visit_to_entries(when: date, mileage: int | None, source: str, lines: list[str], visit: int) -> list[dict]:
    """Turn one visit (its service lines) into proposed entries."""
    performed_by, shop = clean_shop(source)
    lines = [re.sub(r"\s+", " ", ln).strip(" -•·") for ln in lines]
    lines = [ln for ln in lines if ln]
    groups: dict[str, dict] = {}   # key -> {"type", "role", "lines"}
    for ln in lines:
        type_key, role = classify(ln)
        # Unrecognised lines each keep their own title.
        gkey = f"custom:{ln.lower()}" if type_key == "custom" else type_key
        g = groups.setdefault(gkey, {"type": type_key, "role": role, "lines": []})
        # The strongest role wins (a real safety inspection outranks
        # "checked" lines; "50,000 mile service" outranks "Vehicle serviced").
        if RANK[role] > RANK[g["role"]]:
            g["role"] = role
        if ln not in g["lines"]:
            g["lines"].append(ln)

    specific = [g for g in groups.values() if g["role"] in ("work", "check", "plan")]
    # "Vehicle serviced" is only kept when nothing more specific is listed.
    if specific:
        groups = {k: g for k, g in groups.items() if g["role"] != "vague"}
    if not groups and lines:
        groups = {"scheduled_service": {"type": "scheduled_service", "role": "plan", "lines": lines[:1]}}

    all_lines = "; ".join(lines)
    entries = []
    for g in groups.values():
        type_key = g["type"]
        custom = g["lines"][0][:64] if type_key == "custom" else ""
        entries.append({
            "date": when.isoformat(),
            "mileage": mileage,
            "type_key": type_key,
            "custom_type": custom,
            "kind": _kind(type_key, g["lines"]),
            "description": "; ".join(g["lines"])[:480],
            "shop_name": shop,
            "performed_by": performed_by,
            "notes": (f"Imported from Carfax. Everything recorded for this visit: {all_lines}")[:2000],
            "suggested": g["role"] != "admin",
            "visit": visit,
            "_order": {"work": 0, "plan": 1, "vague": 1, "check": 2, "admin": 3}[g["role"]],
        })
    # Real work first, then scheduled service, then inspections, paperwork last.
    entries.sort(key=lambda e: e["_order"])
    for e in entries:
        del e["_order"]
    return entries


def _date(text: str) -> date | None:
    m = DATE_ONLY.match(text.strip())
    if not m:
        return None
    try:
        d = date(int(m.group(3)), int(m.group(1)), int(m.group(2)))
    except ValueError:
        return None
    return d if 1980 <= d.year <= date.today().year + 1 else None


def _miles(text: str) -> int | None:
    m = ODOMETER_ONLY.match(text.strip())
    if not m:
        return None
    value = int(m.group(1).replace(",", ""))
    return value if value < 2_000_000 else None


# ---------------------------------------------------------------------------
# Format 1: Car Care service history (two-column web page printed to PDF)
# ---------------------------------------------------------------------------
CONTROL_WORDS = {"date", "odometer", "edit record", "services performed"}
CARD_END_WORDS = {"upload receipts", "view receipt", "view receipts", "add note", "edit note"}
NAV_WORDS = {"garage", "dashboard", "service history", "maintenance schedule", "repair costs", "add record",
             "invite shop", "(https://www.carfax.com)"}


def is_car_care(layout: list[dict] | None) -> bool:
    return bool(layout) and any(it[2].strip().lower() == "services performed"
                                for page in layout for it in page.get("items", []))


def parse_car_care(layout: list[dict]) -> list[dict]:
    """Read the right-hand visit cards of a Car Care service-history PDF."""
    # 1) The right-hand column, top to bottom, page by page.  The column
    #    edge is just left of the "Services Performed" headings, which only
    #    appear in the cards ("Date" / "Odometer" appear in both columns).
    heads = [float(it[0]) for page in layout for it in page.get("items", [])
             if isinstance(it, list) and len(it) == 3 and str(it[2]).strip().lower() == "services performed"]
    stream: list[str] = []
    for page in layout:
        width = float(page.get("w") or 612)
        edge = (min(heads) - 5) if heads else width * 0.38
        items = [it for it in page.get("items", []) if isinstance(it, list) and len(it) == 3]
        right = [it for it in items if float(it[0]) >= edge]
        right.sort(key=lambda it: (-round(float(it[1]) / 2), float(it[0])))
        stream.extend(str(it[2]).strip() for it in right)

    # 2) Walk through it card by card.
    visits = []
    pending_name: list[str] = []   # text seen since the previous card ended (= next shop name)
    cur = None
    state = "between"
    for text in stream:
        low = text.lower()
        if low == "date":
            cur = {"source": " ".join(pending_name), "date": None, "mileage": None, "lines": []}
            visits.append(cur)
            pending_name, state = [], "header"
            continue
        if low in NAV_WORDS or "/service/" in low or re.fullmatch(r"\d{1,3}", text):
            continue
        if low in CARD_END_WORDS:
            # Receipts / note links close a card; what follows is the next shop.
            state = "between"
            pending_name = []
            continue
        if cur is None or state == "between":
            pending_name.append(text)
            pending_name = pending_name[-4:]
            continue
        if low == "odometer" or low == "edit record":
            continue
        if low == "services performed":
            state = "services"
            continue
        if state == "header":
            d, mi = _date(text), _miles(text)
            if d and cur["date"] is None:
                cur["date"] = d
            elif mi is not None and cur["mileage"] is None:
                cur["mileage"] = mi
            continue
        if state == "services":
            cur["lines"].append(text)

    proposals = []
    for i, v in enumerate(x for x in visits if x["date"] and x["lines"]):
        proposals += visit_to_entries(v["date"], v["mileage"], v["source"], v["lines"], i)
    return proposals


# ---------------------------------------------------------------------------
# Format 2: classic Vehicle History Report (text rows)
# ---------------------------------------------------------------------------
NOT_SERVICE_HINTS = (
    "title issued", "offered for sale", "listed as a dealer vehicle", "registration issued",
    "vehicle sold", "auction", "loan", "lien", "odometer reading reported", "manufacturer",
)


def parse_carfax_text(pages: list[str]) -> list[dict]:
    """Turn the text of a classic Carfax history report into proposed entries."""
    lines: list[str] = []
    for page in pages:
        lines.extend(line.rstrip() for line in page.splitlines())

    records: list[dict] = []
    current: dict | None = None
    for line in lines:
        m = DATE_AT_START.match(line)
        if m:
            month, day, year, rest = int(m.group(1)), int(m.group(2)), int(m.group(3)), m.group(4)
            try:
                when = date(year, month, day)
            except ValueError:
                when = None
            if when and 1980 <= year <= date.today().year + 1:
                current = {"date": when, "first": rest.strip(), "more": []}
                records.append(current)
                continue
        if current is not None and line.strip():
            if re.match(r"^\s*(Date\s+Mileage|Page \d+|CARFAX|Have Questions|Glossary)", line, re.IGNORECASE):
                current = None
                continue
            if len(current["more"]) < 20:
                current["more"].append(line.strip())

    proposals = []
    for i, rec in enumerate(records):
        first = rec["first"]
        mileage = None
        mm = MILEAGE.match(first)
        if mm and mm.group(1).replace(",", "").isdigit():
            value = int(mm.group(1).replace(",", ""))
            if value < 2_000_000:
                mileage = value
                first = first[mm.end():].strip()
        comments = [x.lstrip("-•· ").strip() for x in rec["more"] if x.strip().startswith(("-", "•", "·"))]
        source = first
        if not comments and " - " in first:
            source, _, tail = first.partition(" - ")
            comments = [tail]
        if not comments:
            comments = rec["more"][:1] or [first]
        entries = visit_to_entries(rec["date"], mileage, source, comments, i)
        low = " ".join([first, *rec["more"]]).lower()
        if any(h in low for h in NOT_SERVICE_HINTS):
            for e in entries:
                e["suggested"] = False
        proposals += entries
    return proposals


def parse_carfax(text_pages: list[str], layout: list[dict] | None = None) -> tuple[str, list[dict]]:
    """Pick the right reader for this PDF.  Returns (format name, proposals)."""
    if is_car_care(layout):
        return "car_care", parse_car_care(layout or [])
    return "history_report", parse_carfax_text(text_pages)


# ---------------------------------------------------------------------------
# Reminder defaults offered after an import (typical intervals; the user can
# change any of them).  Only for routine maintenance — never for repairs.
# (miles, months)
# ---------------------------------------------------------------------------
DEFAULT_REMINDERS: dict[str, tuple[int | None, int | None]] = {
    "oil_change": (5000, 6),
    "tire_rotation": (5000, 6),
    "air_filter": (15000, 12),
    "cabin_filter": (15000, 12),
    "brake_fluid": (None, 24),
    "wheel_alignment": (None, 12),
    "differential_fluid": (30000, 36),
    "transmission_fluid": (60000, 60),
    "coolant": (None, 60),
    "spark_plugs": (60000, None),
    "wipers": (None, 12),
    "emissions": (None, 12),
    "inspection": (None, 12),
}
