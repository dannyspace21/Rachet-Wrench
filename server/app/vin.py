"""
vin.py — checking and decoding Vehicle Identification Numbers (VINs).

 * is_valid_vin(): a VIN is 17 characters and its 9th character is a "check
   digit" computed from the others (the official North-American formula).
   This catches most typos and OCR misreads before we ever call the internet.
 * NHTSA decoder: the free US-government vPIC service turns a VIN into year,
   make, model, engine etc.  OUTBOUND CONNECTION #1 (documented in
   SECURITY.md): only the VIN is sent, to https://vpic.nhtsa.dot.gov.
 * SpecProvider: a plug-in point for a paid VIN-spec API.  None ships
   enabled; see README "Adding a paid VIN-spec service".

Important rule: we NEVER invent specs.  Only values the decoder actually
returns are offered, and every one is labelled "From VIN".
"""

from __future__ import annotations

import json
import re
import urllib.error
import urllib.parse
import urllib.request
from typing import Protocol

from .config import get_settings

_TRANSLIT = {
    **{str(d): d for d in range(10)},
    "A": 1, "B": 2, "C": 3, "D": 4, "E": 5, "F": 6, "G": 7, "H": 8,
    "J": 1, "K": 2, "L": 3, "M": 4, "N": 5, "P": 7, "R": 9,
    "S": 2, "T": 3, "U": 4, "V": 5, "W": 6, "X": 7, "Y": 8, "Z": 9,
}
_WEIGHTS = [8, 7, 6, 5, 4, 3, 2, 10, 0, 9, 8, 7, 6, 5, 4, 3, 2]
VIN_CHARS = re.compile(r"^[A-HJ-NPR-Z0-9]{17}$")


def normalize_vin(raw: str) -> str:
    """Upper-case, strip spaces, and fix letters that are illegal in VINs
    (I, O, Q) to the digits they are usually misread from."""
    vin = re.sub(r"[^A-Za-z0-9]", "", raw or "").upper()
    return vin.replace("I", "1").replace("O", "0").replace("Q", "0")


def vin_check_digit(vin: str) -> str:
    """Compute the expected 9th character for a 17-character VIN."""
    total = sum(_TRANSLIT[c] * w for c, w in zip(vin, _WEIGHTS, strict=True))
    rem = total % 11
    return "X" if rem == 10 else str(rem)


def is_valid_vin(vin: str, require_check_digit: bool = True) -> bool:
    """True if the text is a structurally valid VIN.

    European/older vehicles (including many motorcycles) don't always use the
    check digit, so callers can relax that rule for typed-in VINs."""
    if not VIN_CHARS.match(vin or ""):
        return False
    if not require_check_digit:
        return True
    return vin[8] == vin_check_digit(vin)


# ---------------------------------------------------------------------------
# NHTSA vPIC decoder
# ---------------------------------------------------------------------------
# Map of vPIC field name -> our spec key (only fields we trust and show).
# Same rules on Android (Nhtsa.kt); both are checked against
# catalog/vin_tests.json.  Fields NHTSA leaves empty stay absent.
_NHTSA_SPEC_FIELDS = {
    "EngineCylinders": "cylinders",
    "FuelTypePrimary": "fuel_type",
    "BodyClass": "body_style",
    "DriveType": "drive_type",
    "Trim": "trim",
    "Seats": "seating_capacity",
    "EngineModel": "engine_code",
    "ValveTrainDesign": "valve_train",
    "OtherEngineInfo": "engine_details",
    "Turbo": "turbo",
    "ElectrificationLevel": "electrification",
    "Series": "model_code",
    "Doors": "doors",
    "TPMS": "tpms_type",
    "ABS": "abs",
    "ESC": "stability_control",
}


def _num(v, digits: int = 1) -> str | None:
    """'113.20' -> '113.2', '240.0' -> '240'; None if not a number."""
    try:
        x = round(float(v), digits)
    except (TypeError, ValueError):
        return None
    return str(int(x)) if x == int(x) else f"{x:.{digits}f}".rstrip("0").rstrip(".")


def _formatted_specs(r: dict) -> dict:
    """Specs that need a little formatting (units, joined fields)."""
    out = {}
    if r.get("DisplacementL"):
        try:
            out["displacement_l"] = f"{float(r['DisplacementL']):.1f}"
        except ValueError:
            pass
    if r.get("TransmissionStyle"):
        speeds = _num(r.get("TransmissionSpeeds"), 0)
        out["transmission"] = r["TransmissionStyle"] + (f", {speeds}-speed" if speeds and speeds != "1" else "")
    if (hp := _num(r.get("EngineHP"), 0)):
        out["horsepower"] = f"{hp} hp"
    if (w := _num(r.get("CurbWeightLB"), 0)):
        out["curb_weight"] = f"{w} lb"
    if (wb := _num(r.get("WheelBaseShort"), 1)):
        out["wheelbase"] = f"{wb} in"
    place = [re.sub(r"\s*\(.*?\)", "", str(r[k])).strip().title() for k in ("PlantCity", "PlantState", "PlantCountry") if r.get(k)]
    if place:
        out["plant"] = ", ".join(place)
    return out


def _engine_text(r: dict) -> str:
    """Build a short engine description only from fields NHTSA returned."""
    parts = []
    if r.get("DisplacementL"):
        try:
            parts.append(f"{float(r['DisplacementL']):.1f}L")
        except ValueError:
            pass
    if r.get("EngineConfiguration") and r.get("EngineCylinders"):
        parts.append(f"{r['EngineConfiguration'].split('-')[0].strip()}{r['EngineCylinders']}".replace("V-Shaped", "V"))
    elif r.get("EngineCylinders"):
        parts.append(f"{r['EngineCylinders']}-cyl")
    if r.get("EngineModel"):
        parts.append(f"({r['EngineModel']})")
    return " ".join(parts)


def parse_nhtsa_result(r: dict) -> dict:
    """Turn one vPIC 'DecodeVinValues' result into our vehicle + specs shape."""

    def clean(v):
        v = (v or "").strip() if isinstance(v, str) else v
        return v if v not in ("", "Not Applicable", "0") else None

    r = {k: clean(v) for k, v in r.items()}
    vehicle_type = (r.get("VehicleType") or "").upper()
    vehicle = {
        "year": int(r["ModelYear"]) if r.get("ModelYear") and str(r["ModelYear"]).isdigit() else None,
        "make": (r.get("Make") or "").title() if r.get("Make") else None,
        "model": r.get("Model"),
        "trim": r.get("Trim"),
        "kind": "motorcycle" if "MOTORCYCLE" in vehicle_type else ("car" if vehicle_type else None),
    }
    # Lexus/Kia etc. come back upper-case; keep well-known spellings.
    if vehicle["make"] in {"Bmw", "Gmc"}:
        vehicle["make"] = vehicle["make"].upper()
    specs = {}
    for src, key in _NHTSA_SPEC_FIELDS.items():
        if r.get(src):
            specs[key] = str(r[src])
    specs.update(_formatted_specs(r))
    engine = _engine_text(r)
    if engine:
        specs["engine"] = engine
    errors = r.get("ErrorText") or ""
    return {
        "vehicle": {k: v for k, v in vehicle.items() if v},
        "specs": specs,
        "warnings": [] if (r.get("ErrorCode") in (None, "0")) else [errors[:300]],
    }


def decode_with_nhtsa(vin: str, timeout: float = 10.0) -> dict:
    """Call the NHTSA vPIC API.  Raises RuntimeError with a friendly message
    if the service is switched off, unreachable or returns junk."""
    settings = get_settings()
    if not settings.nhtsa_enabled:
        raise RuntimeError("VIN decoding is turned off on this server.")
    url = f"{settings.nhtsa_base_url}/DecodeVinValues/{urllib.parse.quote(vin)}?format=json"
    req = urllib.request.Request(url, headers={"User-Agent": "RatchetWrench/1.0", "Accept": "application/json"})
    try:
        # Reviewed: the URL is a fixed https:// constant plus the URL-quoted,
        # validated VIN (A-Z/0-9 only), so no file:// or other scheme is possible.
        with urllib.request.urlopen(req, timeout=timeout) as resp:  # noqa: S310  # nosemgrep
            body = resp.read(512 * 1024)
    except (urllib.error.URLError, TimeoutError, OSError):
        raise RuntimeError("Could not reach the NHTSA VIN service. Check the server's internet connection.") from None
    try:
        results = json.loads(body.decode("utf-8")).get("Results") or []
    except (UnicodeDecodeError, json.JSONDecodeError, AttributeError):
        raise RuntimeError("The NHTSA VIN service returned an unexpected answer.") from None
    if not results:
        raise RuntimeError("The NHTSA VIN service returned no data for this VIN.")
    return parse_nhtsa_result(results[0])


# NHTSA vPIC model list (for the "Add vehicle" model drop-down)
# ---------------------------------------------------------------------------
_MODEL_CACHE: dict[tuple[str, int | None, str], list[str]] = {}
_MAKE_RE = re.compile(r"^[A-Za-z0-9 .&'-]{1,40}$")


def nhtsa_models(make: str, year: int | None, kind: str, timeout: float = 10.0) -> list[str]:
    """Model names NHTSA knows for a make (and model year), for cars or
    motorcycles.  Only the make name and year are sent.  Cached in memory.
    Raises RuntimeError with a friendly message on failure."""
    settings = get_settings()
    if not settings.nhtsa_enabled:
        raise RuntimeError("Looking up models is turned off on this server.")
    make = (make or "").strip()
    if not _MAKE_RE.match(make):
        raise RuntimeError("Enter a make first.")
    key = (make.lower(), year, kind)
    if key in _MODEL_CACHE:
        return _MODEL_CACHE[key]
    # Cars: passenger cars, SUVs/minivans (MPV) and pickups.  Motorcycles: motorcycles.
    types = ["motorcycle"] if kind == "motorcycle" else ["car", "mpv", "truck"]
    names: set[str] = set()
    for vtype in types:
        path = f"/GetModelsForMakeYear/make/{urllib.parse.quote(make)}"
        if year:
            path += f"/modelyear/{int(year)}"
        path += f"/vehicletype/{vtype}?format=json"
        req = urllib.request.Request(settings.nhtsa_base_url + path,
                                     headers={"User-Agent": "RatchetWrench/1.0", "Accept": "application/json"})
        try:
            # Reviewed: fixed https:// base + URL-quoted make (validated above) + int year.
            with urllib.request.urlopen(req, timeout=timeout) as resp:  # noqa: S310  # nosemgrep
                body = resp.read(1024 * 1024)
            results = json.loads(body.decode("utf-8")).get("Results") or []
        except (urllib.error.URLError, TimeoutError, OSError, UnicodeDecodeError, json.JSONDecodeError, AttributeError):
            raise RuntimeError("Could not reach the NHTSA vehicle database. Type the model instead.") from None
        for r in results:
            name = str(r.get("Model_Name") or "").strip()
            if name:
                names.add(name)
    models = sorted(names, key=str.lower)[:500]
    if len(_MODEL_CACHE) > 500:
        _MODEL_CACHE.clear()
    _MODEL_CACHE[key] = models
    return models


# ---------------------------------------------------------------------------
# Optional paid spec provider plug-in point
# ---------------------------------------------------------------------------
class SpecProvider(Protocol):
    """Anything that can turn a VIN into extra specs (oil capacity, etc.).

    To add a paid service: write a class with this `fetch_specs` method that
    returns {spec_key: value} using ONLY values the service returned, register
    it in PROVIDERS below, and set RW_VIN_SPEC_PROVIDER / RW_VIN_SPEC_API_KEY
    (and RW_VIN_SPEC_API_URL if needed) in .env.  Values it returns are
    labelled "From VIN spec service"."""

    def fetch_specs(self, vin: str) -> dict[str, str]: ...


PROVIDERS: dict[str, type] = {}


def extra_specs(vin: str) -> dict[str, str]:
    """Ask the configured paid provider (if any) for more specs."""
    settings = get_settings()
    cls = PROVIDERS.get(settings.vin_spec_provider)
    if not cls or not settings.vin_spec_api_key:
        return {}
    try:
        return dict(cls().fetch_specs(vin))
    except Exception:
        return {}


_RECALL_CACHE: dict[tuple, tuple[float, list[dict]]] = {}


def _ddmmyyyy(text: str) -> str | None:
    m = re.match(r"^(\d{2})/(\d{2})/(\d{4})$", (text or "").strip())
    return f"{m.group(3)}-{m.group(2)}-{m.group(1)}" if m else None


def nhtsa_recalls(make: str, model: str, year: int, timeout: float = 15.0) -> list[dict]:
    """Every safety recall NHTSA lists for a year/make/model (not per VIN —
    NHTSA's public data can't say which recalls apply to, or were done on, one
    car).  Only make, model and year are sent.  Cached for an hour.
    Raises RuntimeError with a friendly message on failure."""
    import time

    settings = get_settings()
    if not settings.nhtsa_enabled:
        raise RuntimeError("Looking up recalls is turned off on this server.")
    make, model = (make or "").strip(), (model or "").strip()
    if not (_MAKE_RE.match(make) and _MAKE_RE.match(model) and year and 1950 <= int(year) <= 2100):
        raise RuntimeError("Recalls need the vehicle's year, make and model.")
    key = (make.lower(), model.lower(), int(year))
    hit = _RECALL_CACHE.get(key)
    if hit and time.time() - hit[0] < 3600:
        return hit[1]
    q = urllib.parse.urlencode({"make": make, "model": model, "modelYear": int(year)})
    req = urllib.request.Request(f"{settings.nhtsa_recalls_url}/recalls/recallsByVehicle?{q}",
                                 headers={"User-Agent": "RatchetWrench/1.0", "Accept": "application/json"})
    results = None
    for attempt in range(2):  # NHTSA sometimes drops the first request: try once more
        try:
            # Reviewed: fixed https:// base + urlencoded, validated make/model + int year.
            with urllib.request.urlopen(req, timeout=timeout) as resp:  # noqa: S310  # nosemgrep
                results = json.loads(resp.read(4 * 1024 * 1024).decode("utf-8")).get("results") or []
            break
        except (urllib.error.URLError, TimeoutError, OSError, UnicodeDecodeError, json.JSONDecodeError, AttributeError):
            if attempt == 1:
                raise RuntimeError("Could not reach the NHTSA recall database. Try again later.") from None
            time.sleep(1)
    out = []
    for r in results:
        campaign = re.sub(r"[^0-9A-Z]", "", str(r.get("NHTSACampaignNumber") or "").upper())
        if not campaign:
            continue
        out.append({"campaign": campaign[:16], "component": str(r.get("Component") or "")[:255],
                    "summary": str(r.get("Summary") or "")[:5000], "consequence": str(r.get("Consequence") or "")[:5000],
                    "remedy": str(r.get("Remedy") or "")[:5000], "report_date": _ddmmyyyy(str(r.get("ReportReceivedDate") or "")),
                    "park_it": bool(r.get("parkIt")) or bool(r.get("parkOutSide"))})
    if len(_RECALL_CACHE) > 200:
        _RECALL_CACHE.clear()
    _RECALL_CACHE[key] = (time.time(), out)
    return out
