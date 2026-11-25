"""
models.py — the shape of every table in the database.

Design rules that make offline sync on the phone possible:

  * Every ID is a UUID (a random 36-character string).  The phone can make up
    IDs while offline and they will never collide with IDs from the server.
  * Every synced record has created_at / updated_at timestamps.
      - updated_at is "when the user last changed it" (used to decide which
        copy wins when the phone and the server both changed the same record:
        the newest edit wins).
      - server_modified_at is "when the SERVER last stored it" (used by the
        sync endpoint to answer "what changed since my last sync?").
  * Deleting only sets deleted_at ("soft delete").  The row stays so the
    deletion itself can be sent to the phone.
  * Every record carries owner_id so one user can never see another's data.
"""

from __future__ import annotations

import uuid
from datetime import UTC, date, datetime

from sqlalchemy import (
    Boolean,
    Date,
    DateTime,
    ForeignKey,
    Index,
    Integer,
    String,
    Text,
    TypeDecorator,
)
from sqlalchemy.orm import Mapped, mapped_column

from .database import Base


def utcnow() -> datetime:
    """Current time in UTC (time zones are converted only for display)."""
    return datetime.now(UTC)


def new_id() -> str:
    """A new random UUID, as text."""
    return str(uuid.uuid4())


class UTCDateTime(TypeDecorator):
    """A timestamp column that is ALWAYS UTC.

    SQLite has no time-zone support, so we store plain UTC values and attach
    the UTC label again when reading.  This avoids subtle "off by N hours"
    bugs when comparing phone and server timestamps."""

    impl = DateTime
    cache_ok = True

    def process_bind_param(self, value, dialect):
        if value is None:
            return None
        if value.tzinfo is None:
            value = value.replace(tzinfo=UTC)
        return value.astimezone(UTC).replace(tzinfo=None)

    def process_result_value(self, value, dialect):
        if value is None:
            return None
        return value.replace(tzinfo=UTC)


# ---------------------------------------------------------------------------
# Accounts & logins
# ---------------------------------------------------------------------------
class User(Base):
    """A person who can log in.  Passwords are stored only as Argon2 hashes."""

    __tablename__ = "users"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=new_id)
    username: Mapped[str] = mapped_column(String(64), unique=True, index=True)
    password_hash: Mapped[str] = mapped_column(String(255))
    is_admin: Mapped[bool] = mapped_column(Boolean, default=False)
    failed_logins: Mapped[int] = mapped_column(Integer, default=0)
    locked_until: Mapped[datetime | None] = mapped_column(UTCDateTime, nullable=True)
    created_at: Mapped[datetime] = mapped_column(UTCDateTime, default=utcnow)
    updated_at: Mapped[datetime] = mapped_column(UTCDateTime, default=utcnow)


class AuthToken(Base):
    """A login session.  We store only a SHA-256 *hash* of the token, so even
    someone who steals the database file cannot reuse active logins."""

    __tablename__ = "auth_tokens"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=new_id)
    user_id: Mapped[str] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    token_hash: Mapped[str] = mapped_column(String(64), unique=True, index=True)
    client: Mapped[str] = mapped_column(String(16), default="web")  # "web" or "android"
    created_at: Mapped[datetime] = mapped_column(UTCDateTime, default=utcnow)
    expires_at: Mapped[datetime] = mapped_column(UTCDateTime)
    last_used_at: Mapped[datetime | None] = mapped_column(UTCDateTime, nullable=True)
    revoked: Mapped[bool] = mapped_column(Boolean, default=False)


class UserSettings(Base):
    """Per-user preferences (theme, push notifications, units)."""

    __tablename__ = "user_settings"

    user_id: Mapped[str] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), primary_key=True)
    theme: Mapped[str] = mapped_column(String(8), default="system")  # light | dark | system
    distance_unit: Mapped[str] = mapped_column(String(2), default="mi")  # mi | km
    currency: Mapped[str] = mapped_column(String(3), default="USD")
    ntfy_enabled: Mapped[bool] = mapped_column(Boolean, default=False)
    ntfy_topic: Mapped[str] = mapped_column(String(64), default="")
    updated_at: Mapped[datetime] = mapped_column(UTCDateTime, default=utcnow)
    server_modified_at: Mapped[datetime] = mapped_column(UTCDateTime, default=utcnow)


# ---------------------------------------------------------------------------
# Shared columns for every synced record
# ---------------------------------------------------------------------------
class SyncMixin:
    """Columns every synced table shares (see the module comment above)."""

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=new_id)
    owner_id: Mapped[str] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    created_at: Mapped[datetime] = mapped_column(UTCDateTime, default=utcnow)
    updated_at: Mapped[datetime] = mapped_column(UTCDateTime, default=utcnow)
    deleted_at: Mapped[datetime | None] = mapped_column(UTCDateTime, nullable=True)
    server_modified_at: Mapped[datetime] = mapped_column(UTCDateTime, default=utcnow, index=True)


# ---------------------------------------------------------------------------
# Vehicles and everything that belongs to a vehicle
# ---------------------------------------------------------------------------
class Vehicle(SyncMixin, Base):
    """A car or motorcycle."""

    __tablename__ = "vehicles"

    kind: Mapped[str] = mapped_column(String(16), default="car")  # car | motorcycle
    year: Mapped[int | None] = mapped_column(Integer, nullable=True)
    make: Mapped[str] = mapped_column(String(64), default="")
    model: Mapped[str] = mapped_column(String(64), default="")
    trim: Mapped[str] = mapped_column(String(64), default="")
    nickname: Mapped[str] = mapped_column(String(64), default="")
    vin: Mapped[str] = mapped_column(String(17), default="")
    current_mileage: Mapped[int | None] = mapped_column(Integer, nullable=True)
    mileage_unit: Mapped[str] = mapped_column(String(2), default="mi")
    photo_attachment_id: Mapped[str | None] = mapped_column(String(36), nullable=True)
    parts_url: Mapped[str] = mapped_column(String(500), default="")  # optional override
    owners_url: Mapped[str] = mapped_column(String(500), default="")  # optional override
    notes: Mapped[str] = mapped_column(Text, default="")


class VehicleSpec(SyncMixin, Base):
    """One spec value, e.g. key='oil_type' value='0W-20' source='user'.

    source says where the value came from — vin, sticker, user, unverified,
    provider or carfax — and is shown as a label next to the value."""

    __tablename__ = "vehicle_specs"
    __table_args__ = (Index("ix_spec_vehicle_key", "vehicle_id", "key"),)

    vehicle_id: Mapped[str] = mapped_column(ForeignKey("vehicles.id", ondelete="CASCADE"), index=True)
    key: Mapped[str] = mapped_column(String(64))
    value: Mapped[str] = mapped_column(String(255), default="")
    source: Mapped[str] = mapped_column(String(16), default="user")


class Entry(SyncMixin, Base):
    """A maintenance OR repair record (kind tells which)."""

    __tablename__ = "entries"

    vehicle_id: Mapped[str] = mapped_column(ForeignKey("vehicles.id", ondelete="CASCADE"), index=True)
    kind: Mapped[str] = mapped_column(String(16), default="maintenance")  # maintenance | repair
    type_key: Mapped[str] = mapped_column(String(64), default="custom")
    custom_type: Mapped[str] = mapped_column(String(64), default="")  # name when type is "custom"
    date: Mapped[date] = mapped_column(Date)
    mileage: Mapped[int | None] = mapped_column(Integer, nullable=True)
    description: Mapped[str] = mapped_column(String(500), default="")
    parts_used: Mapped[str] = mapped_column(Text, default="")
    cost_cents: Mapped[int | None] = mapped_column(Integer, nullable=True)
    performed_by: Mapped[str] = mapped_column(String(8), default="diy")  # diy | shop
    shop_name: Mapped[str] = mapped_column(String(128), default="")
    notes: Mapped[str] = mapped_column(Text, default="")
    source: Mapped[str] = mapped_column(String(16), default="manual")  # manual | carfax


class Attachment(SyncMixin, Base):
    """An uploaded file (photo, receipt, sticker photo, manual PDF ...).

    The file itself lives in the uploads folder under a random name
    (stored_name).  The original file name is kept only for display."""

    __tablename__ = "attachments"

    vehicle_id: Mapped[str | None] = mapped_column(ForeignKey("vehicles.id", ondelete="CASCADE"), index=True, nullable=True)
    entry_id: Mapped[str | None] = mapped_column(ForeignKey("entries.id", ondelete="CASCADE"), index=True, nullable=True)
    # photo | receipt | sticker | manual | vehicle_photo | carfax
    category: Mapped[str] = mapped_column(String(16), default="photo")
    content_type: Mapped[str] = mapped_column(String(64))
    stored_name: Mapped[str] = mapped_column(String(80), default="")
    original_name: Mapped[str] = mapped_column(String(128), default="")
    caption: Mapped[str] = mapped_column(String(255), default="")
    size_bytes: Mapped[int] = mapped_column(Integer, default=0)
    sha256: Mapped[str] = mapped_column(String(64), default="")
    width: Mapped[int | None] = mapped_column(Integer, nullable=True)
    height: Mapped[int | None] = mapped_column(Integer, nullable=True)
    page_count: Mapped[int | None] = mapped_column(Integer, nullable=True)
    has_file: Mapped[bool] = mapped_column(Boolean, default=False)


class Reminder(SyncMixin, Base):
    """'Do X every N miles or M months, whichever comes first.'"""

    __tablename__ = "reminders"

    vehicle_id: Mapped[str] = mapped_column(ForeignKey("vehicles.id", ondelete="CASCADE"), index=True)
    type_key: Mapped[str] = mapped_column(String(64), default="custom")
    title: Mapped[str] = mapped_column(String(128), default="")
    interval_miles: Mapped[int | None] = mapped_column(Integer, nullable=True)
    interval_months: Mapped[int | None] = mapped_column(Integer, nullable=True)
    last_done_date: Mapped[date | None] = mapped_column(Date, nullable=True)
    last_done_mileage: Mapped[int | None] = mapped_column(Integer, nullable=True)
    enabled: Mapped[bool] = mapped_column(Boolean, default=True)
    notes: Mapped[str] = mapped_column(Text, default="")
    # Bookkeeping for push alerts so we don't spam the same alert.
    last_notified_status: Mapped[str] = mapped_column(String(16), default="")
    last_notified_at: Mapped[datetime | None] = mapped_column(UTCDateTime, nullable=True)


class OdometerReading(SyncMixin, Base):
    """One odometer reading at a point in time."""

    __tablename__ = "odometer_readings"

    vehicle_id: Mapped[str] = mapped_column(ForeignKey("vehicles.id", ondelete="CASCADE"), index=True)
    date: Mapped[date] = mapped_column(Date)
    mileage: Mapped[int] = mapped_column(Integer)
    note: Mapped[str] = mapped_column(String(255), default="")


class Part(SyncMixin, Base):
    """A saved part number (e.g. the OEM oil filter for this vehicle)."""

    __tablename__ = "parts"

    vehicle_id: Mapped[str] = mapped_column(ForeignKey("vehicles.id", ondelete="CASCADE"), index=True)
    type_key: Mapped[str] = mapped_column(String(64), default="custom")
    name: Mapped[str] = mapped_column(String(128), default="")
    part_number: Mapped[str] = mapped_column(String(64), default="")
    brand: Mapped[str] = mapped_column(String(64), default="")
    notes: Mapped[str] = mapped_column(Text, default="")
