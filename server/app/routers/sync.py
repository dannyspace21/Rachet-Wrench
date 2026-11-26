"""
routers/sync.py — keeps the phone's offline copy in step with the server.

  GET  /api/sync?since=<time>   everything of yours that changed after <time>
                                (including deletions), plus `next_since` to
                                use for the following call.
  POST /api/sync                a batch of changes the phone made offline.
                                Each record is applied with "newest edit wins";
                                the response lists the server's final copy of
                                every record so the phone can store it.

Photos/PDFs are uploaded separately with POST /api/attachments (the phone
does that in the background).  Attachment records whose has_file is false
are placeholders still waiting for their upload.
"""

from __future__ import annotations

from datetime import UTC, datetime, timedelta

from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy import select
from sqlalchemy.orm import Session

from .. import schemas
from ..database import get_db
from ..models import User, UserSettings, utcnow
from ..records import ENTITIES, iso, serialize, upsert
from ..security import current_user
from .auth import get_or_create_settings

router = APIRouter(prefix="/api/sync", tags=["sync"])

# Push order matters: parents (vehicles) before children (entries...).
PUSH_ORDER = ["vehicles", "entries", "specs", "reminders", "odometer", "parts", "attachments", "shopping"]
OVERLAP = timedelta(seconds=5)


@router.get("")
def pull(since: datetime | None = Query(None), user: User = Depends(current_user), db: Session = Depends(get_db)) -> dict:
    """Return all records changed after `since` (all records if omitted)."""
    started = utcnow()
    if since is not None and since.tzinfo is None:
        since = since.replace(tzinfo=UTC)
    out: dict = {"server_time": iso(started)}
    for kind, (model, _) in ENTITIES.items():
        q = select(model).where(model.owner_id == user.id)
        if since is not None:
            q = q.where(model.server_modified_at > since)
        out[kind] = [serialize(r) for r in db.scalars(q).all()]
    s: UserSettings = get_or_create_settings(db, user)
    out["settings"] = {k: v for k, v in serialize(s).items() if k != "user_id"}
    # Overlap a few seconds so a write committing during this request is
    # picked up next time.  Records are idempotent, so repeats are harmless.
    out["next_since"] = iso(started - OVERLAP)
    return out


@router.post("")
def push(body: schemas.SyncPushIn, user: User = Depends(current_user), db: Session = Depends(get_db)) -> dict:
    """Apply a batch of offline changes from the phone."""
    results: dict = {}
    for kind in PUSH_ORDER:
        items = getattr(body, kind)
        results[kind] = []
        for item in items:
            if not item.id:
                results[kind].append({"id": None, "status": "rejected", "error": "id is required for sync"})
                continue
            try:
                obj, applied = upsert(db, user, kind, item)
            except HTTPException as exc:
                db.rollback()
                results[kind].append({"id": item.id, "status": "rejected", "error": exc.detail})
                continue
            results[kind].append({
                "id": item.id,
                "status": "applied" if applied else "kept_server_copy",
                "record": serialize(obj) if obj is not None else None,
            })
    return {"server_time": iso(utcnow()), "results": results}
