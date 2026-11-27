"""
routers/vehicles.py — features that act on one whole vehicle:

  POST /api/vin/decode                         VIN -> suggested details/specs
  GET  /api/vehicle-models?make=&year=&kind=   model names (NHTSA) for the Add vehicle form
  POST /api/vehicles/{id}/apply-vin            save VIN-decoded specs
  GET  /api/vehicles/{id}/links                official parts + owner portal
  POST /api/vehicles/{id}/sticker/scan         sticker photos -> proposed fields
  POST /api/vehicles/{id}/sticker/apply        save the fields the user approved
  POST /api/vehicles/{id}/carfax/parse         Carfax PDF -> proposed entries
  POST /api/vehicles/{id}/carfax/apply         save the entries the user approved
  GET  /api/vehicles/{id}/report.pdf           the history report
"""

from __future__ import annotations

import asyncio
from datetime import UTC, date, datetime
import re
import urllib.parse

from fastapi import APIRouter, Depends, File, Form, HTTPException, UploadFile, status
from fastapi.responses import Response
from sqlalchemy import select
from sqlalchemy.orm import Session

from .. import schemas
from ..carfax import DEFAULT_REMINDERS, parse_carfax
from ..catalog import make_links, type_label
from ..config import get_settings
from ..database import get_db
from ..models import Attachment, Entry, OdometerReading, Reminder, User, Vehicle, VehicleRecall, VehicleSpec, utcnow
from ..ocr import extract_from_image, tesseract_available
from ..records import get_owned, serialize, soft_delete, upsert
from ..report import build_report
from ..security import current_user
from ..uploads import accept_upload, file_path, read_limited, run_pdf_worker, sniff_type
from ..spec_parts import recall_id
from ..vin import decode_with_nhtsa, extra_specs, is_valid_vin, nhtsa_models, nhtsa_recalls, normalize_vin
from .auth import get_or_create_settings

router = APIRouter(prefix="/api", tags=["vehicle tools"])


# ---------------------------------------------------------------------------
# VIN
# ---------------------------------------------------------------------------
@router.post("/vin/decode")
async def vin_decode(body: schemas.VinDecodeIn, user: User = Depends(current_user)) -> dict:
    """Decode a VIN with NHTSA (and the optional paid provider).  Nothing is
    saved; the app shows the result for the user to confirm."""
    vin = normalize_vin(body.vin)
    if not is_valid_vin(vin, require_check_digit=False):
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "That doesn't look like a valid 17-character VIN.")
    warnings = []
    if not is_valid_vin(vin):
        warnings.append("The VIN's check digit doesn't match. Double-check it (normal for some non-US vehicles).")
    try:
        result = await asyncio.to_thread(decode_with_nhtsa, vin)
    except RuntimeError as exc:
        raise HTTPException(status.HTTP_502_BAD_GATEWAY, str(exc)) from None
    provider_specs = await asyncio.to_thread(extra_specs, vin)
    specs = [{"key": k, "value": v, "source": "vin"} for k, v in result["specs"].items()]
    specs += [{"key": k, "value": v, "source": "provider"} for k, v in provider_specs.items() if k not in result["specs"]]
    return {"vin": vin, "vehicle": result["vehicle"], "specs": specs, "warnings": warnings + result["warnings"]}


@router.get("/vehicle-models")
async def vehicle_models(make: str, kind: str = "car", year: int | None = None, user: User = Depends(current_user)) -> dict:
    """Model names for a make (and year) from NHTSA, for the Add vehicle form."""
    if kind not in ("car", "motorcycle"):
        kind = "car"
    if year is not None and not 1900 <= year <= 2100:
        year = None
    try:
        models = await asyncio.to_thread(nhtsa_models, make, year, kind)
    except RuntimeError as exc:
        raise HTTPException(status.HTTP_502_BAD_GATEWAY, str(exc)) from None
    return {"make": make, "year": year, "kind": kind, "models": models}


# Lookup rows carry this edit time so ANY change the owner makes wins.
LOOKUP_TIME = datetime(1970, 1, 1, tzinfo=UTC)


@router.post("/vehicles/{vehicle_id}/recalls/refresh")
async def refresh_recalls(vehicle_id: str, user: User = Depends(current_user), db: Session = Depends(get_db)) -> dict:
    """Look up NHTSA recalls for this vehicle's year/make/model and add any
    that aren't listed yet (status "open").  Existing rows — and what the
    owner marked on them — are never changed.  Only year, make and model
    are sent to NHTSA (not the VIN)."""
    v = get_owned(db, Vehicle, vehicle_id, user)
    if not (v.year and v.make and v.model):
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "Add the vehicle's year, make and model to look up recalls.")
    try:
        found = await asyncio.to_thread(nhtsa_recalls, v.make, v.model, v.year)
    except RuntimeError as exc:
        raise HTTPException(status.HTTP_502_BAD_GATEWAY, str(exc)) from None
    now = utcnow()
    added = 0
    for r in found:
        rid = recall_id(v.id, r["campaign"])
        if db.get(VehicleRecall, rid) is not None:
            continue
        db.add(VehicleRecall(id=rid, owner_id=user.id, vehicle_id=v.id, campaign=r["campaign"], component=r["component"],
                             summary=r["summary"], consequence=r["consequence"], remedy=r["remedy"],
                             report_date=date.fromisoformat(r["report_date"]) if r["report_date"] else None,
                             park_it=r["park_it"], status="open", created_at=now, updated_at=LOOKUP_TIME, server_modified_at=now))
        added += 1
    db.commit()
    return {"added": added, "total": len(found)}


@router.post("/vehicles/{vehicle_id}/apply-specs")
def apply_specs(vehicle_id: str, body: schemas.StickerApplyIn, source: str = "vin",
                user: User = Depends(current_user), db: Session = Depends(get_db)) -> list[dict]:
    """Save approved spec values with a source label (vin / provider / user)."""
    if source not in {"vin", "provider", "user", "unverified"}:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "Unknown source.")
    return _save_fields(db, user, vehicle_id, body, source)


def _save_fields(db: Session, user: User, vehicle_id: str, body: schemas.StickerApplyIn, source: str) -> list[dict]:
    vehicle = get_owned(db, Vehicle, vehicle_id, user)
    saved = []
    for f in body.fields:
        if not f.value:
            continue
        if f.key == "vin":
            vin = normalize_vin(f.value)
            if not is_valid_vin(vin, require_check_digit=False):
                raise HTTPException(status.HTTP_400_BAD_REQUEST, "Invalid VIN.")
            vehicle.vin = vin
            vehicle.updated_at = vehicle.server_modified_at = utcnow()
            db.commit()
            continue
        spec, _ = upsert(db, user, "specs", schemas.SpecIn(vehicle_id=vehicle_id, key=f.key, value=f.value, source=source))
        saved.append(serialize(spec))
    return saved


@router.get("/vehicles/{vehicle_id}/links")
def vehicle_links(vehicle_id: str, user: User = Depends(current_user), db: Session = Depends(get_db)) -> dict:
    """Official manufacturer links.  Your per-vehicle overrides win."""
    v = get_owned(db, Vehicle, vehicle_id, user)
    links = make_links(v.make, v.kind)
    return {
        "parts": v.parts_url or links["parts"],
        "owners": v.owners_url or links["owners"],
        "known_make": links["owners"] is not None,
    }


# ---------------------------------------------------------------------------
# Door-jamb sticker scan (web upload path; Android reads text on the phone)
# ---------------------------------------------------------------------------
@router.post("/vehicles/{vehicle_id}/sticker/scan")
async def sticker_scan(
    vehicle_id: str,
    files: list[UploadFile] = File(...),
    use_ai: bool = Form(False),
    user: User = Depends(current_user),
    db: Session = Depends(get_db),
) -> dict:
    """Save the sticker photos to the vehicle and read text from them.
    Returns PROPOSED fields; nothing is saved to specs until /sticker/apply."""
    get_owned(db, Vehicle, vehicle_id, user)
    if not 1 <= len(files) <= 6:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "Upload between 1 and 6 photos.")
    if use_ai and not get_settings().ai_vision_ready:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "AI vision mode is not enabled on this server.")
    if not use_ai and not tesseract_available():
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, "Text recognition is not available on this server.")

    proposed: dict[str, str] = {}
    attachments = []
    raw_texts = []
    for upload in files:
        stored = await accept_upload(upload, allow_pdf=False)
        now = utcnow()
        att = Attachment(owner_id=user.id, vehicle_id=vehicle_id, category="sticker", content_type=stored.content_type,
                         stored_name=stored.stored_name, original_name="Door sticker", size_bytes=stored.size_bytes,
                         sha256=stored.sha256, width=stored.width, height=stored.height, has_file=True,
                         created_at=now, updated_at=now, server_modified_at=now)
        db.add(att)
        db.commit()
        attachments.append(serialize(att))
        try:
            fields, text = await asyncio.to_thread(extract_from_image, file_path(stored.stored_name), use_ai)
        except RuntimeError as exc:
            raise HTTPException(status.HTTP_502_BAD_GATEWAY, str(exc)) from None
        raw_texts.append(text)
        for k, v in fields.items():
            proposed.setdefault(k, v)  # first photo that shows a field wins

    return {
        "fields": [{"key": k, "value": v} for k, v in proposed.items()],
        "attachments": attachments,
        "method": "ai" if use_ai else "ocr",
        "raw_text": "\n---\n".join(raw_texts)[:20000],
    }


@router.post("/vehicles/{vehicle_id}/sticker/apply")
def sticker_apply(vehicle_id: str, body: schemas.StickerApplyIn, user: User = Depends(current_user),
                  db: Session = Depends(get_db)) -> list[dict]:
    """Save the approved fields, labelled 'From door sticker'."""
    return _save_fields(db, user, vehicle_id, body, "sticker")


# ---------------------------------------------------------------------------
# Owner's manual -> fluid specs
# ---------------------------------------------------------------------------
@router.post("/vehicles/{vehicle_id}/manual/scan")
async def manual_scan(vehicle_id: str, attachment_id: str | None = Form(None), file: UploadFile | None = File(None),
                      urls: str | None = Form(None, max_length=8000), text: list[str] | None = Form(None),
                      user: User = Depends(current_user), db: Session = Depends(get_db)) -> dict:
    """Read the owner's manual and PROPOSE fluid specs and capacities, each
    with where it was found.  The manual can be: a PDF saved on the vehicle
    (attachment_id), a new PDF (file; saved), links pasted by the owner
    (urls, one per line: PDFs are saved, web pages are read), or text pasted
    by the owner.  Nothing is saved to Specs until /manual/apply."""
    from ..manual_fetch import LinkError, fetch, parse_links
    from ..manual_specs import find_specs
    from ..uploads import store_bytes

    vehicle = get_owned(db, Vehicle, vehicle_id, user)
    settings = get_settings()
    pages: list[str] = []
    where: list[str] = []      # where each page came from, for the review screen
    saved: list[Attachment] = []

    async def read_pdf(data: bytes, name: str) -> None:
        result = await asyncio.to_thread(run_pdf_worker, data, "text", settings.max_pdf_pages, settings.manual_parse_timeout_s)
        for n, t in enumerate(result.get("text", []), start=1):
            pages.append(t)
            where.append(f"page {n}" + (f" of {name}" if name else ""))

    def save_pdf(stored, caption: str, original: str) -> Attachment:
        now = utcnow()
        att = Attachment(owner_id=user.id, vehicle_id=vehicle.id, category="manual", content_type="application/pdf",
                         stored_name=stored.stored_name, original_name=original[:255], size_bytes=stored.size_bytes,
                         sha256=stored.sha256, page_count=stored.page_count, caption=caption[:255], has_file=True,
                         created_at=now, updated_at=now, server_modified_at=now)
        db.add(att)
        db.commit()
        return att

    if file is not None:
        data = await read_limited(file, settings.max_pdf_mb * 1024 * 1024)
        if sniff_type(data[:16]) != "application/pdf":
            raise HTTPException(status.HTTP_415_UNSUPPORTED_MEDIA_TYPE, "Please choose the owner's manual as a PDF.")
        stored = await asyncio.to_thread(store_bytes, data, True, False)
        saved.append(save_pdf(stored, "Owner's manual", "Owner's manual.pdf"))
        await read_pdf(data, "")
    elif attachment_id:
        att = get_owned(db, Attachment, attachment_id, user)
        if att.vehicle_id != vehicle.id or att.content_type != "application/pdf" or not att.has_file:
            raise HTTPException(status.HTTP_400_BAD_REQUEST, "That file isn't a PDF saved on this vehicle.")
        data = await asyncio.to_thread(file_path(att.stored_name).read_bytes)
        saved.append(att)
        await read_pdf(data, "")
    elif urls and urls.strip():
        if not settings.manual_links_enabled:
            raise HTTPException(status.HTTP_403_FORBIDDEN, "Reading links is turned off on this server. Upload the PDF or paste the text instead.")
        try:
            links = parse_links(urls)
        except LinkError as e:
            raise HTTPException(status.HTTP_400_BAD_REQUEST, str(e)) from None
        for n, link in enumerate(links, start=1):
            try:
                got = await asyncio.to_thread(fetch, link, settings.max_pdf_mb * 1024 * 1024)
            except LinkError as e:
                raise HTTPException(status.HTTP_400_BAD_REQUEST, f"Link {n}: {e}") from None
            host = urllib.parse.urlsplit(got.url).hostname or "link"
            if got.kind == "pdf":
                stored = await asyncio.to_thread(store_bytes, got.data, True, False)
                saved.append(save_pdf(stored, f"Owner's manual (from {host})", got.url.rsplit("/", 1)[-1] or "manual.pdf"))
                await read_pdf(got.data, f"link {n}" if len(links) > 1 else "")
            else:
                pages.append(got.text)
                where.append(f"link {n} ({host})" if len(links) > 1 else host)
    elif text and any(t.strip() for t in text):
        # One or more pasted sections ("Technical data", then "Light bulbs"…), each read as a page.
        sections = [t for t in text if t.strip()][:20]
        if sum(len(t) for t in sections) > 300_000:
            raise HTTPException(status.HTTP_400_BAD_REQUEST, "That's too much text — paste just the specification sections.")
        for n, t in enumerate(sections, start=1):
            pages.append(t)
            where.append("pasted text" if len(sections) == 1 else f"pasted section {n}")
    else:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "Choose the owner's manual: a PDF, links to it, or pasted text.")

    current = {s.key: s.value for s in db.scalars(select(VehicleSpec).where(
        VehicleSpec.owner_id == user.id, VehicleSpec.vehicle_id == vehicle.id, VehicleSpec.deleted_at.is_(None))).all()}
    specs = find_specs(pages)
    for s in specs:
        s["current"] = current.get(s["key"])
        for o in s["options"]:
            o["where"] = where[o["page"] - 1] if 0 < o["page"] <= len(where) else f"page {o['page']}"
    return {"pages": len(pages), "readable_pages": sum(1 for t in pages if (t or "").strip()),
            "attachment": serialize(saved[0]) if saved else None, "attachments": [serialize(a) for a in saved], "specs": specs}


@router.post("/vehicles/{vehicle_id}/manual/apply")
def manual_apply(vehicle_id: str, body: schemas.StickerApplyIn, user: User = Depends(current_user),
                 db: Session = Depends(get_db)) -> list[dict]:
    """Save the values approved on the review screen as "From owner's manual"."""
    return _save_fields(db, user, vehicle_id, body, "manual")


# ---------------------------------------------------------------------------
# Carfax
# ---------------------------------------------------------------------------
@router.post("/vehicles/{vehicle_id}/carfax/parse")
async def carfax_parse(vehicle_id: str, file: UploadFile = File(...), user: User = Depends(current_user),
                       db: Session = Depends(get_db)) -> dict:
    """Read a Carfax PDF and propose entries for the review screen.
    The PDF is also stored on the vehicle for your records."""
    vehicle = get_owned(db, Vehicle, vehicle_id, user)
    settings = get_settings()
    data = await read_limited(file, settings.max_pdf_mb * 1024 * 1024)
    if sniff_type(data[:16]) != "application/pdf":
        raise HTTPException(status.HTTP_415_UNSUPPORTED_MEDIA_TYPE, "Please upload the Carfax report as a PDF.")
    result = await asyncio.to_thread(run_pdf_worker, data, "layout", min(settings.max_pdf_pages, 60))
    report_format, proposals = parse_carfax(result.get("text", []), result.get("layout"))

    # Carfax mileage is in miles; convert for vehicles kept in kilometres.
    if vehicle.mileage_unit == "km":
        for p in proposals:
            if p["mileage"] is not None:
                p["mileage"] = round(p["mileage"] * 1.609344)

    # Mark records that are already in the history (e.g. a second import).
    live = db.scalars(select(Entry).where(Entry.owner_id == user.id, Entry.vehicle_id == vehicle_id,
                                          Entry.deleted_at.is_(None))).all()
    have = {(e.date.isoformat(), e.type_key, (e.custom_type or "").lower()) for e in live if e.source != "carfax"}
    have_carfax = {(e.date.isoformat(), e.type_key, (e.custom_type or "").lower()) for e in live if e.source == "carfax"}
    for p in proposals:
        key = (p["date"], p["type_key"], (p["custom_type"] or "").lower())
        p["duplicate"] = key in have or key in have_carfax
        if key in have:
            p["suggested"] = False

    from ..uploads import store_bytes

    stored = await asyncio.to_thread(store_bytes, data, True, False)
    now = utcnow()
    att = Attachment(owner_id=user.id, vehicle_id=vehicle_id, category="carfax", content_type="application/pdf",
                     stored_name=stored.stored_name, original_name="Carfax report.pdf", size_bytes=stored.size_bytes,
                     sha256=stored.sha256, page_count=stored.page_count, has_file=True,
                     created_at=now, updated_at=now, server_modified_at=now)
    db.add(att)
    db.commit()
    dated = [p for p in proposals if p["mileage"] is not None]
    newest = max(dated, key=lambda p: (p["date"], p["mileage"]), default=None)
    return {
        "format": report_format,
        "entries": proposals,
        "visits": len({p["visit"] for p in proposals}),
        "previous_imports": len(have_carfax),
        "newest_mileage": newest["mileage"] if newest else None,
        "newest_date": newest["date"] if newest else None,
        "attachment": serialize(att),
        "pages": result["pages"],
    }


@router.post("/vehicles/{vehicle_id}/carfax/apply")
def carfax_apply(vehicle_id: str, body: schemas.CarfaxApplyIn, user: User = Depends(current_user),
                 db: Session = Depends(get_db)) -> dict:
    """Save the entries the user approved on the review screen, then (if
    asked) add odometer readings and bring reminders up to date."""
    vehicle = get_owned(db, Vehicle, vehicle_id, user)
    now = utcnow()

    removed = 0
    if body.replace_previous:
        for old in db.scalars(select(Entry).where(Entry.owner_id == user.id, Entry.vehicle_id == vehicle_id,
                                                  Entry.source == "carfax", Entry.deleted_at.is_(None))).all():
            soft_delete(db, old, now)
            removed += 1
        db.commit()

    live = db.scalars(select(Entry).where(Entry.owner_id == user.id, Entry.vehicle_id == vehicle_id,
                                          Entry.deleted_at.is_(None))).all()
    have = {(e.date, e.type_key, (e.custom_type or "").lower(), (e.description or "").lower()) for e in live}
    created, skipped = [], 0
    # Oldest first, so "last done" ends on the newest record.
    for e in sorted(body.entries, key=lambda x: x.date):
        key = (e.date, e.type_key, e.custom_type.lower(), e.description.lower())
        if key in have:
            skipped += 1
            continue
        values = e.model_dump()
        if values["performed_by"] == "diy":
            values["shop_name"] = ""
        obj, _ = upsert(db, user, "entries", schemas.EntryIn(vehicle_id=vehicle_id, source="carfax", **values))
        have.add(key)
        created.append(serialize(obj))

    # Odometer readings, one per date (highest mileage that day).
    readings_added = 0
    if body.update_odometer:
        per_day: dict = {}
        for e in body.entries:
            if e.mileage is not None:
                per_day[e.date] = max(per_day.get(e.date, 0), e.mileage)
        existing = {(r.date, r.mileage) for r in db.scalars(select(OdometerReading).where(
            OdometerReading.owner_id == user.id, OdometerReading.vehicle_id == vehicle_id,
            OdometerReading.deleted_at.is_(None))).all()}
        for day, mileage in sorted(per_day.items()):
            if (day, mileage) in existing:
                continue
            upsert(db, user, "odometer", schemas.OdometerIn(vehicle_id=vehicle_id, date=day, mileage=mileage, note="From Carfax"))
            readings_added += 1

    # Reminders: newest record of each routine-maintenance type.
    reminders_created, reminders_updated = [], []
    if body.update_reminders:
        newest: dict = {}
        for e in body.entries:
            if e.kind != "maintenance" or e.type_key not in DEFAULT_REMINDERS:
                continue
            # Only a real (state/safety) inspection is a yearly event; routine
            # "brakes checked" style inspections don't get a reminder.
            if e.type_key == "inspection" and not re.search(r"safety|state", e.description, re.IGNORECASE):
                continue
            cur = newest.get(e.type_key)
            if cur is None or (e.date, e.mileage or 0) > (cur[0], cur[1] or 0):
                newest[e.type_key] = (e.date, e.mileage)
        km = vehicle.mileage_unit == "km"
        for type_key, (day, mileage) in newest.items():
            rems = db.scalars(select(Reminder).where(Reminder.owner_id == user.id, Reminder.vehicle_id == vehicle_id,
                                                     Reminder.type_key == type_key, Reminder.deleted_at.is_(None))).all()
            if rems:
                moved = False
                for rem in rems:
                    if rem.last_done_date is None or day >= rem.last_done_date:
                        upsert(db, user, "reminders", schemas.ReminderIn(
                            last_done_date=day, last_done_mileage=mileage if mileage is not None else rem.last_done_mileage),
                            record_id=rem.id)
                        moved = True
                if moved:
                    reminders_updated.append(type_label(type_key))
                continue
            miles, months = DEFAULT_REMINDERS[type_key]
            if miles and km:
                miles = int(round(miles * 1.609344, -3))
            upsert(db, user, "reminders", schemas.ReminderIn(
                vehicle_id=vehicle_id, type_key=type_key, interval_miles=miles, interval_months=months,
                last_done_date=day, last_done_mileage=mileage, enabled=True,
                notes="Created from a Carfax import. The interval is a typical one — change it to match your owner's manual."))
            reminders_created.append(type_label(type_key))

    db.refresh(vehicle)
    return {
        "created": len(created),
        "skipped_duplicates": skipped,
        "removed_previous": removed,
        "odometer_readings_added": readings_added,
        "current_mileage": vehicle.current_mileage,
        "reminders_created": reminders_created,
        "reminders_updated": reminders_updated,
        "entries": created,
    }


# ---------------------------------------------------------------------------
# History report
# ---------------------------------------------------------------------------
@router.get("/vehicles/{vehicle_id}/report.pdf")
async def history_report(vehicle_id: str, user: User = Depends(current_user), db: Session = Depends(get_db)) -> Response:
    """Download the full maintenance & repair history as a PDF."""
    vehicle = get_owned(db, Vehicle, vehicle_id, user)
    prefs = get_or_create_settings(db, user)
    pdf = await asyncio.to_thread(build_report, db, user, vehicle, prefs.currency, prefs.distance_unit)
    name = re.sub(r"[^A-Za-z0-9]+", "-", " ".join(str(x) for x in (vehicle.year, vehicle.make, vehicle.model) if x)).strip("-")
    return Response(
        pdf,
        media_type="application/pdf",
        headers={
            "Content-Disposition": f'attachment; filename="{name or "vehicle"}-history.pdf"',
            "X-Content-Type-Options": "nosniff",
            "Cache-Control": "no-store",
        },
    )


@router.get("/vehicles/{vehicle_id}/specs-table")
def specs_table(vehicle_id: str, user: User = Depends(current_user), db: Session = Depends(get_db)) -> list[dict]:
    """Every spec field from the catalog with its current value (or blank)."""
    from ..catalog import load_catalog
    from sqlalchemy import select

    get_owned(db, Vehicle, vehicle_id, user)
    rows = {s.key: s for s in db.scalars(select(VehicleSpec).where(
        VehicleSpec.vehicle_id == vehicle_id, VehicleSpec.owner_id == user.id, VehicleSpec.deleted_at.is_(None)))}
    out = []
    for field in load_catalog()["specs"]:
        s = rows.get(field["key"])
        out.append({**field, "id": s.id if s else None, "value": s.value if s else "", "source": s.source if s else None})
    return out
