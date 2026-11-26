"""
records.py — one shared set of rules for creating, changing and deleting
records, used by BOTH the normal API and the phone's sync endpoint, so the
two can never behave differently.

Rules enforced here:
  * Ownership — you can only read or change records whose owner_id is you.
    Someone else's record looks exactly like a missing one (404), so IDs of
    other people's data can't even be confirmed.
  * References — a record's vehicle_id / entry_id must point at one of YOUR
    vehicles / entries.
  * Newest edit wins — if the phone and the server both changed a record,
    the copy with the later updated_at is kept.
  * Soft delete — deleting sets deleted_at; deleting a vehicle also marks
    everything belonging to it as deleted, so the phone learns about it.
"""

from __future__ import annotations

from datetime import date, datetime, timedelta

from fastapi import HTTPException, status
from pydantic import BaseModel
from sqlalchemy import select
from sqlalchemy.orm import Session

from . import schemas
from .models import Attachment, Entry, OdometerReading, Part, Reminder, ShoppingItem, User, Vehicle, VehicleSpec, utcnow
from .reminders import mark_done_from_entry
from .spec_parts import apply_spec
from .uploads import delete_stored

# kind name -> (table class, input schema)
ENTITIES: dict[str, tuple[type, type[BaseModel]]] = {
    "vehicles": (Vehicle, schemas.VehicleIn),
    "specs": (VehicleSpec, schemas.SpecIn),
    "entries": (Entry, schemas.EntryIn),
    "reminders": (Reminder, schemas.ReminderIn),
    "odometer": (OdometerReading, schemas.OdometerIn),
    "parts": (Part, schemas.PartIn),
    "attachments": (Attachment, schemas.AttachmentIn),
    "shopping": (ShoppingItem, schemas.ShoppingItemIn),
}

# Columns never sent to clients (internal bookkeeping / storage details).
HIDDEN = {"owner_id", "stored_name", "last_notified_status", "last_notified_at"}

# Fields a client must provide when creating a record of each kind.
REQUIRED_ON_CREATE = {
    "specs": ("vehicle_id", "key"),
    "entries": ("vehicle_id", "date"),
    "reminders": ("vehicle_id",),
    "odometer": ("vehicle_id", "date", "mileage"),
    "parts": ("vehicle_id",),
    "shopping": ("description",),
}


def iso(value):
    """Format dates/times for JSON (UTC times end in 'Z')."""
    if isinstance(value, datetime):
        return value.isoformat().replace("+00:00", "Z")
    if isinstance(value, date):
        return value.isoformat()
    return value


def serialize(obj) -> dict:
    """Turn a database row into a plain dictionary for the API."""
    return {c.name: iso(getattr(obj, c.name)) for c in obj.__table__.columns if c.name not in HIDDEN}


def not_found() -> HTTPException:
    return HTTPException(status.HTTP_404_NOT_FOUND, "Not found.")


def get_owned(db: Session, model: type, record_id: str, user: User, include_deleted: bool = False):
    """Fetch a record that belongs to `user`, or raise 404."""
    obj = db.get(model, record_id)
    if obj is None or obj.owner_id != user.id or (obj.deleted_at is not None and not include_deleted):
        raise not_found()
    return obj


def _check_vehicle_ref(db: Session, user: User, vehicle_id: str | None) -> None:
    """Make sure vehicle_id is one of the user's (non-deleted) vehicles."""
    if vehicle_id is None:
        return
    v = db.get(Vehicle, vehicle_id)
    if v is None or v.owner_id != user.id or v.deleted_at is not None:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "Unknown vehicle.")


def _clamp_updated_at(value: datetime | None) -> datetime:
    """Use the client's edit time, but never one in the future (a phone with
    a wrong clock must not make its edits impossible to override)."""
    now = utcnow()
    if value is None:
        return now
    if value.tzinfo is None:
        from datetime import UTC

        value = value.replace(tzinfo=UTC)
    return min(value, now + timedelta(minutes=1))


def soft_delete(db: Session, obj, when: datetime | None = None) -> None:
    """Mark a record (and its children) as deleted."""
    now = utcnow()
    when = when or now
    obj.deleted_at = when
    obj.updated_at = when
    obj.server_modified_at = now
    children = []
    if isinstance(obj, Vehicle):
        for model in (VehicleSpec, Entry, Reminder, OdometerReading, Part, Attachment, ShoppingItem):
            children += db.scalars(select(model).where(model.vehicle_id == obj.id, model.deleted_at.is_(None))).all()
    elif isinstance(obj, VehicleSpec):
        apply_spec(db, obj)  # its auto part goes too
    elif isinstance(obj, Entry):
        children += db.scalars(select(Attachment).where(Attachment.entry_id == obj.id, Attachment.deleted_at.is_(None))).all()
    for child in children:
        child.deleted_at = when
        child.updated_at = when
        child.server_modified_at = now
    # Deleted photos/PDFs are erased from disk right away (privacy); only the
    # small record remains so the deletion can sync to the phone.
    for item in [obj, *children]:
        if isinstance(item, Attachment) and item.stored_name:
            delete_stored(item.stored_name)
            item.stored_name = ""
            item.has_file = False


def _bump_vehicle_mileage(db: Session, vehicle_id: str | None, mileage: int | None) -> None:
    """If a new entry/odometer reading shows a higher mileage, update the
    vehicle's current mileage too."""
    if not vehicle_id or mileage is None:
        return
    v = db.get(Vehicle, vehicle_id)
    if v and (v.current_mileage is None or mileage > v.current_mileage):
        now = utcnow()
        v.current_mileage = mileage
        v.updated_at = now
        v.server_modified_at = now


def upsert(db: Session, user: User, kind: str, data: BaseModel, record_id: str | None = None) -> tuple[object, bool]:
    """Create or update one record.  Returns (row, applied).

    `applied` is False when the server already had a NEWER version (the
    incoming change lost the 'newest edit wins' comparison)."""
    model, _schema = ENTITIES[kind]
    values = data.model_dump(exclude_unset=True)
    record_id = record_id or values.pop("id", None)
    values.pop("id", None)
    incoming_updated = _clamp_updated_at(values.pop("updated_at", None))
    deleted_at = values.pop("deleted_at", None)
    now = utcnow()

    existing = db.get(model, record_id) if record_id else None
    if existing is not None and existing.owner_id != user.id:
        raise not_found()

    # Specs are unique per (vehicle, field): reuse the live row if present.
    if existing is None and kind == "specs" and values.get("vehicle_id") and values.get("key"):
        existing = db.scalar(
            select(VehicleSpec).where(
                VehicleSpec.owner_id == user.id,
                VehicleSpec.vehicle_id == values["vehicle_id"],
                VehicleSpec.key == values["key"],
                VehicleSpec.deleted_at.is_(None),
            )
        )

    if existing is not None:
        if existing.updated_at and existing.updated_at > incoming_updated:
            return existing, False  # server copy is newer: keep it
        if deleted_at is not None:
            if existing.deleted_at is None:
                soft_delete(db, existing, _clamp_updated_at(deleted_at))
            db.commit()
            return existing, True
        if existing.deleted_at is not None:
            return existing, False  # can't edit something already deleted
        obj = existing
    else:
        if kind == "attachments":
            raise HTTPException(status.HTTP_400_BAD_REQUEST, "Upload attachments with POST /api/attachments.")
        if deleted_at is not None:
            # Deleting something the server never saw: nothing to do.
            return None, True
        for field in REQUIRED_ON_CREATE.get(kind, ()):
            if values.get(field) in (None, ""):
                raise HTTPException(status.HTTP_400_BAD_REQUEST, f"'{field}' is required.")
        if kind == "vehicles" and not (values.get("make") or values.get("nickname")):
            raise HTTPException(status.HTTP_400_BAD_REQUEST, "Give the vehicle a make or a nickname.")
        obj = model(owner_id=user.id, created_at=now)
        if record_id:
            obj.id = record_id
        # The new row joins the database session only AFTER validation below,
        # so a rejected request can never leave a half-built record behind.

    # Validate references to other records.
    if "vehicle_id" in values:
        if values["vehicle_id"] is None and kind not in ("attachments", "shopping"):
            raise HTTPException(status.HTTP_400_BAD_REQUEST, "'vehicle_id' is required.")
        _check_vehicle_ref(db, user, values["vehicle_id"])
        if existing is not None and kind not in ("attachments", "shopping") and values["vehicle_id"] != existing.vehicle_id:
            raise HTTPException(status.HTTP_400_BAD_REQUEST, "Records can't be moved to another vehicle.")
    if kind == "shopping":
        # Links to a reminder / saved part are only hints: one that isn't
        # yours (or no longer exists) is dropped instead of losing the item.
        for field, ref in (("reminder_id", Reminder), ("part_id", Part)):
            if values.get(field):
                row = db.get(ref, values[field])
                if row is None or row.owner_id != user.id:
                    values[field] = None
        if "description" in values and not str(values["description"] or "").strip():
            raise HTTPException(status.HTTP_400_BAD_REQUEST, "Describe the item.")
    if kind == "attachments" and values.get("entry_id"):
        get_owned(db, Entry, values["entry_id"], user)
    if kind == "vehicles" and values.get("photo_attachment_id"):
        get_owned(db, Attachment, values["photo_attachment_id"], user)

    for key, value in values.items():
        setattr(obj, key, value)
    if existing is None:
        db.add(obj)
    if kind == "specs" and not obj.source:
        obj.source = "user"
    obj.updated_at = incoming_updated
    obj.server_modified_at = now
    db.flush()

    # Side effects that keep related data consistent.
    if kind == "entries":
        _bump_vehicle_mileage(db, obj.vehicle_id, obj.mileage)
        # Any new record (maintenance OR repair, e.g. a tire rotation logged as
        # a repair) moves the matching reminders forward.
        if existing is None:
            mark_done_from_entry(db, user.id, obj.vehicle_id, obj.type_key, obj.date, obj.mileage)
    elif kind == "odometer":
        _bump_vehicle_mileage(db, obj.vehicle_id, obj.mileage)
    elif kind == "specs":
        apply_spec(db, obj)  # e.g. oil filter part number -> Parts tab
    db.commit()
    return obj, True


def list_owned(db: Session, user: User, kind: str, vehicle_id: str | None = None) -> list:
    """All live records of a kind for this user (optionally one vehicle)."""
    model, _ = ENTITIES[kind]
    q = select(model).where(model.owner_id == user.id, model.deleted_at.is_(None))
    if vehicle_id:
        q = q.where(model.vehicle_id == vehicle_id)
    return list(db.scalars(q).all())
