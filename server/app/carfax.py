"""
carfax.py — pulls service-history records out of a Carfax PDF report.

Carfax reports list history as rows of:  Date | Mileage | Source | Comments.
After text extraction (done safely in pdf_worker.py) every record begins
with a date like 03/15/2019.  We:

 1. split the text into records at each date,
 2. pick out the mileage and the source (usually the shop name),
 3. guess the maintenance/repair type from keywords in the comments,
 4. mark records that look like service work as "suggested" and things like
    "Title issued" or "Vehicle offered for sale" as not suggested.

The result is only a PROPOSAL.  The user reviews every row (edit / approve /
reject) before anything is saved.  We never invent costs or details.
"""

from __future__ import annotations

import re
from datetime import date

DATE_AT_START = re.compile(r"^\s*(\d{1,2})/(\d{1,2})/(\d{4})\b\s*(.*)$")
MILEAGE = re.compile(r"\b(\d{1,3}(?:,\d{3})+|\d{3,6})\s*(?:mi\b|miles\b)?", re.IGNORECASE)

# keyword (lower case)  ->  type key.  First match wins, so specific
# phrases come before generic ones.
TYPE_RULES: list[tuple[str, str]] = [
    ("oil and filter", "oil_change"), ("oil change", "oil_change"), ("oil changed", "oil_change"),
    ("oil filter", "oil_filter"),
    ("tires rotated", "tire_rotation"), ("tire rotation", "tire_rotation"), ("rotated tires", "tire_rotation"),
    ("tire replaced", "tire_replacement"), ("tires replaced", "tire_replacement"), ("new tire", "tire_replacement"),
    ("tire pressure", "tire_pressure"), ("tpms", "tpms_sensor"),
    ("tire repaired", "tire_repair"), ("flat", "tire_repair"), ("puncture", "tire_repair"),
    ("alignment", "wheel_alignment"), ("balanced", "wheel_balance"), ("balance", "wheel_balance"),
    ("brake pad", "brake_pads"), ("brake rotor", "brake_rotors"), ("rotors", "brake_rotors"),
    ("brake fluid", "brake_fluid"), ("brake", "brake_pads"),
    ("transmission", "transmission_fluid"), ("coolant", "coolant"), ("antifreeze", "coolant"),
    ("radiator", "radiator"), ("water pump", "water_pump"), ("thermostat", "thermostat"),
    ("power steering", "power_steering_fluid"), ("differential", "differential_fluid"),
    ("transfer case", "transfer_case_fluid"), ("cabin air filter", "cabin_filter"), ("cabin filter", "cabin_filter"),
    ("air filter", "air_filter"), ("fuel filter", "fuel_filter"), ("spark plug", "spark_plugs"),
    ("ignition coil", "ignition_coils"), ("serpentine", "serpentine_belt"), ("drive belt", "serpentine_belt"),
    ("timing belt", "timing_belt"), ("timing chain", "timing_chain"), ("battery", "battery"),
    ("wiper", "wipers"), ("bulb", "lights_bulbs"), ("headlight", "lights_bulbs"), ("light", "lights_bulbs"),
    ("a/c", "ac_service"), ("air conditioning", "ac_service"), ("alternator", "alternator"),
    ("starter", "starter"), ("strut", "shocks_struts"), ("shock", "shocks_struts"), ("suspension", "suspension"),
    ("steering", "steering"), ("cv ", "cv_axles"), ("axle", "cv_axles"), ("wheel bearing", "wheel_bearings"),
    ("clutch", "clutch"), ("exhaust", "exhaust"), ("muffler", "exhaust"), ("catalytic", "catalytic_converter"),
    ("emissions", "emissions"), ("inspection", "inspection"), ("inspected", "inspection"),
    ("recall", "recall"), ("registration", "registration"), ("registered", "registration"),
    ("windshield", "glass"), ("glass", "glass"), ("detail", "detailing"), ("wash", "detailing"),
    ("diagnos", "diagnostics"), ("check engine", "diagnostics"),
    ("accident", "accident_damage"), ("damage", "accident_damage"), ("collision", "accident_damage"),
    ("chain", "chain_adjustment"), ("sprocket", "sprockets"), ("fork", "fork_oil"), ("valve", "valve_adjustment"),
    ("maintenance", "custom"), ("serviced", "custom"),
]

SERVICE_HINTS = (
    "serviced", "service", "maintenance", "oil", "filter", "tire", "brake", "replaced", "repaired",
    "inspect", "rotated", "fluid", "battery", "alignment", "emissions", "recall", "installed", "checked",
)
NOT_SERVICE_HINTS = (
    "title issued", "offered for sale", "listed as a dealer vehicle", "registration issued",
    "vehicle sold", "auction", "loan", "lien", "odometer reading reported", "manufacturer",
)
REPAIR_HINTS = ("repair", "replaced", "damage", "accident", "collision", "leak", "failed", "broken", "recall")


def _guess_type(text: str) -> str:
    low = text.lower()
    for keyword, key in TYPE_RULES:
        if keyword in low:
            return key
    return "custom"


def _guess_kind(text: str, type_key: str) -> str:
    low = text.lower()
    if type_key in {"accident_damage", "general_repair", "recall", "tire_repair", "diagnostics"}:
        return "repair"
    # "replaced" on a normal wear item (filters, wipers...) is still maintenance.
    wear = {"oil_change", "oil_filter", "air_filter", "cabin_filter", "wipers", "spark_plugs", "tire_rotation",
            "tire_replacement", "wheel_alignment", "wheel_balance", "brake_fluid", "coolant", "transmission_fluid",
            "fuel_filter", "serpentine_belt", "timing_belt", "lights_bulbs", "inspection", "emissions", "registration"}
    if type_key not in wear and any(h in low for h in REPAIR_HINTS):
        return "repair"
    return "maintenance"


def parse_carfax_text(pages: list[str]) -> list[dict]:
    """Turn the text of a Carfax report into a list of proposed entries."""
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
            # Stop collecting at obvious report section headings.
            if re.match(r"^\s*(Date\s+Mileage|Page \d+|CARFAX|Have Questions|Glossary)", line, re.IGNORECASE):
                current = None
                continue
            if len(current["more"]) < 12:
                current["more"].append(line.strip())

    proposals = []
    for rec in records:
        first = rec["first"]
        mileage = None
        mm = MILEAGE.match(first)
        if mm:
            value = int(mm.group(1).replace(",", ""))
            if value < 2_000_000:
                mileage = value
                first = first[mm.end():].strip()
        # Comments usually start with "- " or are on following lines.
        comment_lines = [x.lstrip("-•· ").strip() for x in rec["more"] if x.strip().startswith(("-", "•", "·"))]
        other_lines = [x for x in rec["more"] if not x.strip().startswith(("-", "•", "·"))]
        source = first
        if not comment_lines and " - " in first:
            source, _, tail = first.partition(" - ")
            comment_lines = [tail]
        description = "; ".join(c for c in comment_lines if c)[:480] or (other_lines[0][:480] if other_lines else first[:480])
        full = " ".join([first, *rec["more"]])
        low = full.lower()
        type_key = _guess_type(description or full)
        suggested = any(h in low for h in SERVICE_HINTS) and not any(h in low for h in NOT_SERVICE_HINTS)
        proposals.append({
            "date": rec["date"].isoformat(),
            "mileage": mileage,
            "type_key": type_key,
            "kind": _guess_kind(full, type_key),
            "description": description or "Carfax record",
            "shop_name": re.sub(r"\s{2,}", " ", source)[:120],
            "performed_by": "shop",
            "notes": ("From Carfax: " + " / ".join(rec["more"]))[:2000],
            "suggested": suggested,
        })
    return proposals
