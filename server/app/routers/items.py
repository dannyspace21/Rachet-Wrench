"""
routers/items.py — the standard list / get / create / update / delete
endpoints for each kind of record.  One generic builder is used for all of
them so every kind gets the same security checks (see records.py).

    GET    /api/<kind>?vehicle_id=...   list (newest first where sensible)
    POST   /api/<kind>                  create
    GET    /api/<kind>/{id}             read one
    PATCH  /api/<kind>/{id}             change some fields
    DELETE /api/<kind>/{id}             soft delete
"""

# (No "from __future__ import annotations" here: FastAPI must see the real
# schema classes in the generated functions below.)

from fastapi import APIRouter, Depends, Query
from sqlalchemy.orm import Session

from ..database import get_db
from ..models import User, Vehicle
from ..records import ENTITIES, get_owned, list_owned, serialize, soft_delete, upsert
from ..reminders import reminder_status
from ..schemas import UUID_RE
from ..security import current_user

router = APIRouter(prefix="/api", tags=["records"])


def _decorate(db: Session, kind: str, obj) -> dict:
    """Serialize a record, adding computed info for some kinds."""
    data = serialize(obj)
    if kind == "reminders":
        v = db.get(Vehicle, obj.vehicle_id)
        data["due"] = reminder_status(obj, v.current_mileage if v else None)
    return data


def _sort(kind: str, rows: list) -> list:
    if kind in ("entries", "odometer"):
        return sorted(rows, key=lambda r: (r.date, r.mileage or 0), reverse=True)
    if kind == "vehicles":
        return sorted(rows, key=lambda r: r.created_at)
    return rows


def make_routes(kind: str) -> None:
    """Register the five endpoints for one record kind."""
    model, schema = ENTITIES[kind]

    def list_items(
        vehicle_id: str | None = Query(None, pattern=UUID_RE),
        user: User = Depends(current_user),
        db: Session = Depends(get_db),
    ) -> list[dict]:
        if vehicle_id:
            get_owned(db, Vehicle, vehicle_id, user)
        rows = list_owned(db, user, kind, vehicle_id if kind != "vehicles" else None)
        return [_decorate(db, kind, r) for r in _sort(kind, rows)]

    def get_item(item_id: str, user: User = Depends(current_user), db: Session = Depends(get_db)) -> dict:
        return _decorate(db, kind, get_owned(db, model, item_id, user))

    def create_item(body: schema, user: User = Depends(current_user), db: Session = Depends(get_db)) -> dict:  # type: ignore[valid-type]
        obj, _ = upsert(db, user, kind, body)
        return _decorate(db, kind, obj)

    def update_item(item_id: str, body: schema, user: User = Depends(current_user), db: Session = Depends(get_db)) -> dict:  # type: ignore[valid-type]
        get_owned(db, model, item_id, user)
        body.updated_at = None  # interactive edits always count as "now"
        obj, _ = upsert(db, user, kind, body, record_id=item_id)
        return _decorate(db, kind, obj)

    def delete_item(item_id: str, user: User = Depends(current_user), db: Session = Depends(get_db)) -> dict:
        obj = get_owned(db, model, item_id, user)
        soft_delete(db, obj)
        db.commit()
        return {"ok": True}

    tag = [kind]
    router.add_api_route(f"/{kind}", list_items, methods=["GET"], tags=tag, summary=f"List {kind}")
    if kind != "attachments":  # attachments are created by file upload
        router.add_api_route(f"/{kind}", create_item, methods=["POST"], tags=tag, status_code=201, summary=f"Create {kind}")
    router.add_api_route(f"/{kind}/{{item_id}}", get_item, methods=["GET"], tags=tag, summary=f"Get one of {kind}")
    router.add_api_route(f"/{kind}/{{item_id}}", update_item, methods=["PATCH"], tags=tag, summary=f"Update one of {kind}")
    router.add_api_route(f"/{kind}/{{item_id}}", delete_item, methods=["DELETE"], tags=tag, summary=f"Delete one of {kind}")


for _kind in ENTITIES:
    make_routes(_kind)
