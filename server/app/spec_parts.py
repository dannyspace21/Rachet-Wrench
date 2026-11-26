"""
spec_parts.py — specs that are also parts.

Filling in certain specs (oil type, oil filter part number, tire sizes,
battery group, wiper sizes…) automatically adds that part to the vehicle's
Parts tab, and changing the spec updates it.  The list lives in the shared
catalog ("spec_parts"), so the phone does exactly the same offline.

Each auto part has a FIXED id made from the vehicle and the spec key (an
MD5 name-based UUID, identical to Java's UUID.nameUUIDFromBytes), so when
both the phone and the server add it, it's the same record — never two.

Rules:
  * new / changed spec   -> part created / its number updated
  * spec cleared/deleted -> the auto part is removed
  * a part you deleted yourself stays deleted (until the spec changes)
  * a part you already saved by hand with the same number isn't added again
"""

from __future__ import annotations

import hashlib
import uuid

from sqlalchemy import select
from sqlalchemy.orm import Session

from .catalog import load_catalog
from .models import Part, VehicleSpec, utcnow


def spec_part_id(vehicle_id: str, key: str) -> str:
    """Same result as Java UUID.nameUUIDFromBytes("ratchet-wrench:spec-part:<vehicle>:<key>")."""
    b = bytearray(hashlib.md5(f"ratchet-wrench:spec-part:{vehicle_id}:{key}".encode(), usedforsecurity=False).digest())
    b[6] = (b[6] & 0x0F) | 0x30  # version 3 (name-based, MD5)
    b[8] = (b[8] & 0x3F) | 0x80  # IETF variant
    return str(uuid.UUID(bytes=bytes(b)))


def _mapping() -> dict[str, dict]:
    return {m["spec"]: m for m in load_catalog().get("spec_parts", [])}


def apply_spec(db: Session, spec: VehicleSpec) -> None:
    """Bring the auto part for this spec in line with it (no commit)."""
    m = _mapping().get(spec.key)
    if m is None:
        return
    now = utcnow()
    pid = spec_part_id(spec.vehicle_id, spec.key)
    part = db.get(Part, pid)
    value = (spec.value or "").strip() if spec.deleted_at is None else ""
    if not value:
        if part is not None and part.deleted_at is None:
            part.deleted_at = part.updated_at = part.server_modified_at = now
        return
    number = m["format"].replace("{v}", value)[:64]
    if part is None:
        # Already saved by hand (same kind of part, same number)? Don't add it twice.
        same = db.scalar(select(Part).where(Part.owner_id == spec.owner_id, Part.vehicle_id == spec.vehicle_id,
                                            Part.type_key == m["type_key"], Part.part_number == number, Part.deleted_at.is_(None)))
        if same is not None:
            return
        db.add(Part(id=pid, owner_id=spec.owner_id, vehicle_id=spec.vehicle_id, type_key=m["type_key"],
                    name=m["name"], part_number=number, notes="Added from Specs", created_at=now,
                    updated_at=now, server_modified_at=now))
    elif part.deleted_at is None and part.part_number != number:
        part.part_number = number
        part.updated_at = part.server_modified_at = now
    elif part.deleted_at is not None and part.part_number != number:
        # Deleted earlier, but the spec has changed since: bring it back.
        part.deleted_at = None
        part.part_number = number
        part.updated_at = part.server_modified_at = now


def backfill(db: Session) -> int:
    """Add missing auto parts for specs saved before this feature existed.
    Never re-adds one that was deleted.  Returns how many were added."""
    m = _mapping()
    if not m:
        return 0
    added = 0
    specs = db.scalars(select(VehicleSpec).where(VehicleSpec.deleted_at.is_(None), VehicleSpec.key.in_(m.keys()))).all()
    for s in specs:
        if (s.value or "").strip() and db.get(Part, spec_part_id(s.vehicle_id, s.key)) is None:
            apply_spec(db, s)
            added += 1
    db.commit()
    return added
