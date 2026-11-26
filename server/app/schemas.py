"""
schemas.py — the exact shape and limits of data the API accepts and returns.

Every request body is checked against these rules BEFORE it touches the
database (wrong types, over-long text, unknown fields, impossible numbers are
rejected with a clear error).  This is the server-side input validation that
OWASP recommends: never trust what the browser or phone sends.
"""

from __future__ import annotations

import re
import datetime as dt
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator

from .catalog import SPEC_SOURCES, spec_keys, type_keys

UUID_RE = r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
UUIDStr = Annotated[str, Field(pattern=UUID_RE)]
Short = Annotated[str, Field(max_length=128)]
Text = Annotated[str, Field(max_length=5000)]
Mileage = Annotated[int, Field(ge=0, le=5_000_000)]
CostCents = Annotated[int, Field(ge=0, le=100_000_000)]  # up to $1,000,000


class Strict(BaseModel):
    """Base for request bodies: unknown fields are an error, text is trimmed."""

    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)


def _check_url(value: str) -> str:
    """Only plain https:// links (or empty) — blocks javascript: and data: URLs."""
    if value and not re.match(r"^https://[^\s<>\"']+$", value):
        raise ValueError("Links must start with https://")
    return value


# ---------------------------------------------------------------------------
# Auth
# ---------------------------------------------------------------------------
class LoginIn(Strict):
    username: Annotated[str, Field(min_length=1, max_length=64)]
    password: Annotated[str, Field(min_length=1, max_length=256)]
    client: Literal["web", "android"] = "web"


class PairStartIn(Strict):
    """Make a "connect your phone" QR code.  address = how the PHONE reaches
    this server, e.g. http://192.168.1.20:8077 (a guess is used if omitted)."""

    address: Annotated[str, Field(max_length=200)] | None = None


class PairClaimIn(Strict):
    code: Annotated[str, Field(min_length=10, max_length=100)]


class SetupIn(Strict):
    """First-run account creation (desktop app)."""

    token: Annotated[str, Field(min_length=16, max_length=200)]
    username: Annotated[str, Field(min_length=3, max_length=64, pattern=r"^[a-zA-Z0-9_.-]+$")]
    password: Annotated[str, Field(min_length=1, max_length=256)]


class ChangePasswordIn(Strict):
    current_password: Annotated[str, Field(min_length=1, max_length=256)]
    new_password: Annotated[str, Field(min_length=1, max_length=256)]


class CreateUserIn(Strict):
    username: Annotated[str, Field(min_length=3, max_length=64, pattern=r"^[a-zA-Z0-9_.-]+$")]
    password: Annotated[str, Field(min_length=1, max_length=256)]
    is_admin: bool = False


class SettingsIn(Strict):
    theme: Literal["light", "dark", "system"] | None = None
    distance_unit: Literal["mi", "km"] | None = None
    currency: Annotated[str, Field(pattern=r"^[A-Z]{3}$")] | None = None
    ntfy_enabled: bool | None = None
    # ntfy topics act like passwords: anyone who knows one can read it.
    ntfy_topic: Annotated[str, Field(max_length=64, pattern=r"^[A-Za-z0-9_-]*$")] | None = None


# ---------------------------------------------------------------------------
# Synced records.  All fields optional so the same model serves create,
# partial update and sync.  `id` lets the phone choose the UUID offline.
# ---------------------------------------------------------------------------
class SyncFields(Strict):
    id: UUIDStr | None = None
    updated_at: dt.datetime | None = None
    deleted_at: dt.datetime | None = None


class VehicleIn(SyncFields):
    kind: Literal["car", "motorcycle"] | None = None
    year: Annotated[int, Field(ge=1886, le=2100)] | None = None
    make: Annotated[str, Field(max_length=64)] | None = None
    model: Annotated[str, Field(max_length=64)] | None = None
    trim: Annotated[str, Field(max_length=64)] | None = None
    nickname: Annotated[str, Field(max_length=64)] | None = None
    vin: Annotated[str, Field(max_length=17, pattern=r"^[A-HJ-NPR-Za-hj-npr-z0-9]*$")] | None = None
    current_mileage: Mileage | None = None
    mileage_unit: Literal["mi", "km"] | None = None
    photo_attachment_id: UUIDStr | None = None
    parts_url: Annotated[str, Field(max_length=500)] | None = None
    owners_url: Annotated[str, Field(max_length=500)] | None = None
    notes: Text | None = None

    @field_validator("parts_url", "owners_url")
    @classmethod
    def safe_links(cls, v):
        return _check_url(v) if v else v

    @field_validator("vin")
    @classmethod
    def upper_vin(cls, v):
        return v.upper() if v else v


class SpecIn(SyncFields):
    vehicle_id: UUIDStr | None = None
    key: Annotated[str, Field(max_length=64)] | None = None
    value: Annotated[str, Field(max_length=255)] | None = None
    source: str | None = None

    @field_validator("key")
    @classmethod
    def known_key(cls, v):
        if v is not None and v not in spec_keys():
            raise ValueError("Unknown spec field")
        return v

    @field_validator("source")
    @classmethod
    def known_source(cls, v):
        if v is not None and v not in SPEC_SOURCES:
            raise ValueError("Unknown spec source")
        return v


def _known_type(v):
    if v is not None and v not in type_keys():
        raise ValueError("Unknown maintenance/repair type")
    return v


class EntryIn(SyncFields):
    vehicle_id: UUIDStr | None = None
    kind: Literal["maintenance", "repair"] | None = None
    type_key: str | None = None
    custom_type: Annotated[str, Field(max_length=64)] | None = None
    date: dt.date | None = None
    mileage: Mileage | None = None
    description: Annotated[str, Field(max_length=500)] | None = None
    parts_used: Text | None = None
    cost_cents: CostCents | None = None
    performed_by: Literal["diy", "shop"] | None = None
    shop_name: Short | None = None
    notes: Text | None = None
    source: Literal["manual", "carfax"] | None = None

    @field_validator("type_key")
    @classmethod
    def valid_type(cls, v):
        return _known_type(v)


class ReminderIn(SyncFields):
    vehicle_id: UUIDStr | None = None
    type_key: str | None = None
    title: Short | None = None
    interval_miles: Annotated[int, Field(ge=1, le=1_000_000)] | None = None
    interval_months: Annotated[int, Field(ge=1, le=240)] | None = None
    last_done_date: dt.date | None = None
    last_done_mileage: Mileage | None = None
    enabled: bool | None = None
    notes: Text | None = None

    @field_validator("type_key")
    @classmethod
    def valid_type(cls, v):
        return _known_type(v)


class OdometerIn(SyncFields):
    vehicle_id: UUIDStr | None = None
    date: dt.date | None = None
    mileage: Mileage | None = None
    note: Annotated[str, Field(max_length=255)] | None = None


class PartIn(SyncFields):
    vehicle_id: UUIDStr | None = None
    type_key: str | None = None
    name: Short | None = None
    part_number: Annotated[str, Field(max_length=64)] | None = None
    brand: Annotated[str, Field(max_length=64)] | None = None
    notes: Text | None = None

    @field_validator("type_key")
    @classmethod
    def valid_type(cls, v):
        return _known_type(v)


class ShoppingItemIn(SyncFields):
    """A shopping-list line.  vehicle_id may be null (a general item)."""

    vehicle_id: UUIDStr | None = None
    reminder_id: UUIDStr | None = None
    part_id: UUIDStr | None = None
    type_key: str | None = None
    description: Annotated[str, Field(max_length=200)] | None = None
    part_number: Annotated[str, Field(max_length=64)] | None = None
    spec: Annotated[str, Field(max_length=200)] | None = None
    quantity: Annotated[int, Field(ge=1, le=999)] | None = None
    purchased: bool | None = None
    notes: Text | None = None

    @field_validator("type_key")
    @classmethod
    def valid_type(cls, v):
        return _known_type(v)


class AttachmentIn(SyncFields):
    """Metadata changes only (caption, links); the file itself is uploaded
    through POST /api/attachments."""

    vehicle_id: UUIDStr | None = None
    entry_id: UUIDStr | None = None
    category: Literal["photo", "receipt", "sticker", "manual", "vehicle_photo", "carfax"] | None = None
    caption: Annotated[str, Field(max_length=255)] | None = None


class SyncPushIn(Strict):
    """A batch of changes from the phone.  Max 500 records per kind per call."""

    vehicles: Annotated[list[VehicleIn], Field(max_length=500)] = []
    specs: Annotated[list[SpecIn], Field(max_length=500)] = []
    entries: Annotated[list[EntryIn], Field(max_length=500)] = []
    reminders: Annotated[list[ReminderIn], Field(max_length=500)] = []
    odometer: Annotated[list[OdometerIn], Field(max_length=500)] = []
    parts: Annotated[list[PartIn], Field(max_length=500)] = []
    attachments: Annotated[list[AttachmentIn], Field(max_length=500)] = []
    shopping: Annotated[list[ShoppingItemIn], Field(max_length=500)] = []


class StickerFieldIn(Strict):
    key: str
    value: Annotated[str, Field(max_length=255)]

    @field_validator("key")
    @classmethod
    def known(cls, v):
        if v != "vin" and v not in spec_keys():
            raise ValueError("Unknown field")
        return v


class StickerApplyIn(Strict):
    """The fields the user approved on the sticker review screen."""

    fields: Annotated[list[StickerFieldIn], Field(max_length=60)]


class CarfaxEntryIn(Strict):
    date: dt.date
    mileage: Mileage | None = None
    type_key: str = "custom"
    custom_type: Annotated[str, Field(max_length=64)] = ""
    kind: Literal["maintenance", "repair"] = "maintenance"
    description: Annotated[str, Field(max_length=500)] = ""
    shop_name: Short = ""
    performed_by: Literal["diy", "shop"] = "shop"
    notes: Text = ""
    cost_cents: CostCents | None = None

    @field_validator("type_key")
    @classmethod
    def valid_type(cls, v):
        return _known_type(v)


class CarfaxApplyIn(Strict):
    entries: Annotated[list[CarfaxEntryIn], Field(max_length=1000)]
    # Add an odometer reading for each dated mileage in the report.
    update_odometer: bool = True
    # Move reminders' "last done" forward, and create reminders (with
    # typical, editable intervals) for routine maintenance that has none.
    update_reminders: bool = True
    # Delete entries from EARLIER Carfax imports of this vehicle first
    # (for redoing an import that went wrong).
    replace_previous: bool = False


class VinDecodeIn(Strict):
    vin: Annotated[str, Field(min_length=11, max_length=20)]
