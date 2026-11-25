"""
routers/attachments.py — uploading and downloading photos, receipts,
sticker photos and manual PDFs.

Uploads run through uploads.py (type check, size limit, metadata removal,
random file names).  Downloads are only possible for the owner, and are
sent with headers that stop browsers from treating them as web pages or
scripts (nosniff + a sandboxing Content-Security-Policy).
"""

from __future__ import annotations

import re

from fastapi import APIRouter, Depends, File, Form, HTTPException, UploadFile, status
from fastapi.responses import FileResponse
from sqlalchemy.orm import Session

from ..database import get_db
from ..models import Attachment, Entry, User, Vehicle, utcnow
from ..records import get_owned, serialize
from ..schemas import UUID_RE
from ..security import current_user
from ..uploads import accept_upload, file_path, safe_display_name, thumb_path

router = APIRouter(prefix="/api/attachments", tags=["attachments"])

CATEGORIES = {"photo", "receipt", "sticker", "manual", "vehicle_photo", "carfax"}
IMAGE_ONLY = {"photo", "sticker", "vehicle_photo"}
PDF_ONLY = {"manual", "carfax"}

FILE_HEADERS = {
    "X-Content-Type-Options": "nosniff",
    "Content-Security-Policy": "default-src 'none'; img-src 'self'; style-src 'unsafe-inline'; sandbox",
    "Cache-Control": "private, max-age=3600",
    "Cross-Origin-Resource-Policy": "same-origin",
}


@router.post("", status_code=201)
async def upload(
    file: UploadFile = File(...),
    vehicle_id: str = Form(...),
    category: str = Form("photo"),
    entry_id: str | None = Form(None),
    caption: str = Form(""),
    id: str | None = Form(None),  # noqa: A002 - lets the phone choose the UUID offline
    user: User = Depends(current_user),
    db: Session = Depends(get_db),
) -> dict:
    """Upload one file and attach it to a vehicle (and optionally an entry)."""
    if category not in CATEGORIES:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "Unknown category.")
    for value in (vehicle_id, entry_id, id):
        if value and not re.match(UUID_RE, value):
            raise HTTPException(status.HTTP_400_BAD_REQUEST, "Invalid ID.")
    if len(caption) > 255:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "Caption is too long.")
    get_owned(db, Vehicle, vehicle_id, user)
    if entry_id:
        entry = get_owned(db, Entry, entry_id, user)
        if entry.vehicle_id != vehicle_id:
            raise HTTPException(status.HTTP_400_BAD_REQUEST, "Entry belongs to another vehicle.")

    # Idempotent re-upload from the phone (e.g. after a dropped connection).
    if id:
        existing = db.get(Attachment, id)
        if existing is not None:
            if existing.owner_id != user.id or existing.deleted_at is not None:
                raise HTTPException(status.HTTP_404_NOT_FOUND, "Not found.")
            if existing.has_file:
                return serialize(existing)

    stored = await accept_upload(file, allow_pdf=category not in IMAGE_ONLY, allow_images=category not in PDF_ONLY)
    now = utcnow()
    att = db.get(Attachment, id) if id else None
    if att is None:
        att = Attachment(owner_id=user.id, created_at=now)
        if id:
            att.id = id
        db.add(att)
    att.vehicle_id = vehicle_id
    att.entry_id = entry_id
    att.category = category
    att.caption = caption.strip()
    att.content_type = stored.content_type
    att.stored_name = stored.stored_name
    att.original_name = safe_display_name(file.filename)
    att.size_bytes = stored.size_bytes
    att.sha256 = stored.sha256
    att.width, att.height, att.page_count = stored.width, stored.height, stored.page_count
    att.has_file = True
    att.updated_at = now
    att.server_modified_at = now
    if category == "vehicle_photo":
        v = db.get(Vehicle, vehicle_id)
        v.photo_attachment_id = att.id
        v.updated_at = now
        v.server_modified_at = now
    db.commit()
    return serialize(att)


def _download_name(att: Attachment) -> str:
    ext = {"image/jpeg": ".jpg", "image/png": ".png", "application/pdf": ".pdf"}.get(att.content_type, "")
    base = re.sub(r"\.[A-Za-z0-9]+$", "", att.original_name or "") or att.category
    return re.sub(r"[^A-Za-z0-9 ._()-]", "", base)[:80] + ext


@router.get("/{attachment_id}/file")
def download(attachment_id: str, user: User = Depends(current_user), db: Session = Depends(get_db)) -> FileResponse:
    """Download the original (cleaned) file.  PDFs are always sent as a
    download, never displayed inline, so the browser's PDF viewer can't run
    anything embedded in them."""
    att = get_owned(db, Attachment, attachment_id, user)
    if not att.has_file:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "File not uploaded yet.")
    inline = att.content_type.startswith("image/")
    return FileResponse(
        file_path(att.stored_name),
        media_type=att.content_type,
        filename=_download_name(att),
        content_disposition_type="inline" if inline else "attachment",
        headers=FILE_HEADERS,
    )


@router.get("/{attachment_id}/thumb")
def thumbnail(attachment_id: str, user: User = Depends(current_user), db: Session = Depends(get_db)) -> FileResponse:
    """A small JPEG preview of a photo (for lists and galleries)."""
    att = get_owned(db, Attachment, attachment_id, user)
    path = thumb_path(att.stored_name) if att.has_file and att.content_type.startswith("image/") else None
    if path is None or not path.is_file():
        raise HTTPException(status.HTTP_404_NOT_FOUND, "No preview.")
    return FileResponse(path, media_type="image/jpeg", headers=FILE_HEADERS)
