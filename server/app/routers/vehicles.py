"""
routers/vehicles.py — features that act on one whole vehicle:

  POST /api/vin/decode                         VIN -> suggested details/specs
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
import re

from fastapi import APIRouter, Depends, File, Form, HTTPException, UploadFile, status
from fastapi.responses import Response
from sqlalchemy.orm import Session

from .. import schemas
from ..carfax import parse_carfax_text
from ..catalog import make_links
from ..config import get_settings
from ..database import get_db
from ..models import Attachment, User, Vehicle, VehicleSpec, utcnow
from ..ocr import extract_from_image, tesseract_available
from ..records import get_owned, serialize, upsert
from ..report import build_report
from ..security import current_user
from ..uploads import accept_upload, file_path, read_limited, run_pdf_worker, sniff_type
from ..vin import decode_with_nhtsa, extra_specs, is_valid_vin, normalize_vin
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
    links = make_links(v.make)
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
# Carfax
# ---------------------------------------------------------------------------
@router.post("/vehicles/{vehicle_id}/carfax/parse")
async def carfax_parse(vehicle_id: str, file: UploadFile = File(...), user: User = Depends(current_user),
                       db: Session = Depends(get_db)) -> dict:
    """Read a Carfax PDF and propose entries for the review screen.
    The PDF is also stored on the vehicle for your records."""
    get_owned(db, Vehicle, vehicle_id, user)
    settings = get_settings()
    data = await read_limited(file, settings.max_pdf_mb * 1024 * 1024)
    if sniff_type(data[:16]) != "application/pdf":
        raise HTTPException(status.HTTP_415_UNSUPPORTED_MEDIA_TYPE, "Please upload the Carfax report as a PDF.")
    result = await asyncio.to_thread(run_pdf_worker, data, "text", min(settings.max_pdf_pages, 60))
    proposals = parse_carfax_text(result.get("text", []))
    from ..uploads import store_bytes

    stored = await asyncio.to_thread(store_bytes, data, True, False)
    now = utcnow()
    att = Attachment(owner_id=user.id, vehicle_id=vehicle_id, category="carfax", content_type="application/pdf",
                     stored_name=stored.stored_name, original_name="Carfax report.pdf", size_bytes=stored.size_bytes,
                     sha256=stored.sha256, page_count=stored.page_count, has_file=True,
                     created_at=now, updated_at=now, server_modified_at=now)
    db.add(att)
    db.commit()
    return {"entries": proposals, "attachment": serialize(att), "pages": result["pages"]}


@router.post("/vehicles/{vehicle_id}/carfax/apply")
def carfax_apply(vehicle_id: str, body: schemas.CarfaxApplyIn, user: User = Depends(current_user),
                 db: Session = Depends(get_db)) -> dict:
    """Create the entries the user approved on the review screen."""
    get_owned(db, Vehicle, vehicle_id, user)
    created = []
    for e in body.entries:
        obj, _ = upsert(db, user, "entries", schemas.EntryIn(vehicle_id=vehicle_id, source="carfax", **e.model_dump()))
        created.append(serialize(obj))
    return {"created": len(created), "entries": created}


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
