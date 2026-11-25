"""
seed.py — fills an EMPTY account with sample vehicles and history so the
app (and the README screenshots) look realistic.  Only runs when
RW_SEED_SAMPLE_DATA=true and the admin user has no vehicles yet.

Vehicles: Lexus RC 350, Lexus IS 350, Lexus RC F, 2014 Kia Forte and Ducati
Monster 696.  Model years for the Lexus and Ducati were not given, so they
are left blank.  Specs are only filled where the fact is certain for every
model year (e.g. the RC F's 5.0 L V8), and even those are labelled
"Unverified" so you know to confirm them.  Part numbers are marked "sample —
verify fitment".  Photos are simple illustrations drawn here, not real.
"""

from __future__ import annotations

import io
import logging
from datetime import date, timedelta

from PIL import Image, ImageDraw
from sqlalchemy import select
from sqlalchemy.orm import Session

from .models import Attachment, Entry, OdometerReading, Part, Reminder, User, Vehicle, VehicleSpec, utcnow
from .uploads import store_bytes

log = logging.getLogger("ratchetwrench.seed")


def _illustration(kind: str, label: str, color: tuple[int, int, int]) -> bytes:
    """Draw a simple 1200x900 illustration (stands in for a real photo)."""
    img = Image.new("RGB", (1200, 900), (236, 240, 244))
    d = ImageDraw.Draw(img)
    # soft floor/background gradient
    for y in range(900):
        shade = 236 - int(y / 900 * 60)
        d.line([(0, y), (1200, y)], fill=(shade, shade + 2, shade + 6))
    cx, cy = 600, 470
    if kind == "filter":
        d.rounded_rectangle([cx - 170, cy - 230, cx + 170, cy + 230], 40, fill=color, outline=(30, 30, 30), width=6)
        for i in range(-180, 200, 40):
            d.line([(cx - 170, cy + i), (cx + 170, cy + i)], fill=tuple(max(0, c - 40) for c in color), width=8)
        d.ellipse([cx - 90, cy - 300, cx + 90, cy - 200], fill=(70, 70, 70))
    elif kind == "brake":
        d.ellipse([cx - 300, cy - 300, cx + 300, cy + 300], fill=(150, 155, 160), outline=(60, 60, 60), width=8)
        d.ellipse([cx - 90, cy - 90, cx + 90, cy + 90], fill=(90, 95, 100))
        for r in range(130, 290, 22):
            d.arc([cx - r, cy - r, cx + r, cy + r], 0, 360, fill=(125, 130, 135), width=3)
        d.rounded_rectangle([cx + 150, cy - 220, cx + 330, cy + 220], 50, fill=color, outline=(30, 30, 30), width=6)
    elif kind == "tire":
        d.ellipse([cx - 320, cy - 320, cx + 320, cy + 320], fill=(35, 35, 38))
        d.ellipse([cx - 200, cy - 200, cx + 200, cy + 200], fill=(185, 190, 196))
        for a in range(0, 360, 72):
            d.pieslice([cx - 190, cy - 190, cx + 190, cy + 190], a, a + 30, fill=(140, 145, 150))
        d.ellipse([cx - 50, cy - 50, cx + 50, cy + 50], fill=(70, 70, 75))
    elif kind == "chain":
        for i in range(-5, 6):
            x = cx + i * 95
            d.rounded_rectangle([x - 55, cy - 40, x + 55, cy + 40], 30, outline=color, width=18)
    elif kind == "receipt":
        d.rectangle([cx - 230, cy - 380, cx + 230, cy + 380], fill=(255, 255, 255), outline=(200, 200, 200), width=3)
        for i in range(12):
            w = 380 if i % 3 else 240
            d.rectangle([cx - 190, cy - 320 + i * 52, cx - 190 + w, cy - 300 + i * 52], fill=(190, 195, 200))
        d.rectangle([cx - 190, cy + 300, cx + 190, cy + 330], fill=color)
    elif kind == "battery":
        d.rounded_rectangle([cx - 260, cy - 170, cx + 260, cy + 200], 24, fill=(40, 44, 52))
        d.rectangle([cx - 200, cy - 220, cx - 120, cy - 170], fill=(200, 60, 60))
        d.rectangle([cx + 120, cy - 220, cx + 200, cy - 170], fill=(60, 60, 60))
        d.rectangle([cx - 260, cy - 40, cx + 260, cy + 10], fill=color)
    d.text((40, 840), label, fill=(60, 70, 80))
    out = io.BytesIO()
    img.save(out, format="JPEG", quality=85)
    return out.getvalue()


def _attach(db: Session, owner: User, vehicle: Vehicle, entry: Entry | None, category: str, data: bytes, caption: str) -> Attachment:
    stored = store_bytes(data, allow_pdf=False)
    now = utcnow()
    att = Attachment(owner_id=owner.id, vehicle_id=vehicle.id, entry_id=entry.id if entry else None, category=category,
                     content_type=stored.content_type, stored_name=stored.stored_name, original_name=caption,
                     caption=caption, size_bytes=stored.size_bytes, sha256=stored.sha256, width=stored.width,
                     height=stored.height, has_file=True, created_at=now, updated_at=now, server_modified_at=now)
    db.add(att)
    return att


def seed_sample_data(db: Session) -> None:
    """Add the sample vehicles to the first admin account if it is empty."""
    owner = db.scalar(select(User).where(User.is_admin.is_(True)).order_by(User.created_at))
    if owner is None or db.scalar(select(Vehicle).where(Vehicle.owner_id == owner.id)):
        return
    today = date.today()
    now = utcnow()

    def vehicle(**kw) -> Vehicle:
        v = Vehicle(owner_id=owner.id, created_at=now, updated_at=now, server_modified_at=now, **kw)
        db.add(v)
        db.flush()
        return v

    def spec(v: Vehicle, key: str, value: str, source: str = "unverified") -> None:
        db.add(VehicleSpec(owner_id=owner.id, vehicle_id=v.id, key=key, value=value, source=source,
                           created_at=now, updated_at=now, server_modified_at=now))

    def entry(v: Vehicle, days_ago: int, **kw) -> Entry:
        e = Entry(owner_id=owner.id, vehicle_id=v.id, date=today - timedelta(days=days_ago),
                  created_at=now, updated_at=now, server_modified_at=now, **kw)
        db.add(e)
        db.flush()
        return e

    def reminder(v: Vehicle, **kw) -> None:
        db.add(Reminder(owner_id=owner.id, vehicle_id=v.id, created_at=now, updated_at=now, server_modified_at=now, **kw))

    def odo(v: Vehicle, days_ago: int, miles: int, note: str = "") -> None:
        db.add(OdometerReading(owner_id=owner.id, vehicle_id=v.id, date=today - timedelta(days=days_ago), mileage=miles,
                               note=note, created_at=now, updated_at=now, server_modified_at=now))

    def part(v: Vehicle, **kw) -> None:
        db.add(Part(owner_id=owner.id, vehicle_id=v.id, created_at=now, updated_at=now, server_modified_at=now, **kw))

    # ---- Lexus RC 350 ----------------------------------------------------
    rc = vehicle(kind="car", make="Lexus", model="RC 350", nickname="RC", current_mileage=48210)
    spec(rc, "engine", "3.5L V6")
    spec(rc, "oil_type", "0W-20 full synthetic", "user")
    e1 = entry(rc, 35, kind="maintenance", type_key="oil_change", mileage=47950, description="Oil and filter change",
               parts_used="Synthetic 0W-20 oil, OEM oil filter, drain plug gasket", cost_cents=8995,
               performed_by="diy", notes="Torqued drain plug to spec. Reset maintenance light.")
    _attach(db, owner, rc, e1, "photo", _illustration("filter", "New oil filter", (220, 90, 30)), "New oil filter")
    _attach(db, owner, rc, e1, "receipt", _illustration("receipt", "Parts receipt", (234, 88, 12)), "Parts receipt")
    entry(rc, 35, kind="maintenance", type_key="tire_rotation", mileage=47950, description="Rotated tires front to back",
          cost_cents=0, performed_by="diy", notes="Staggered setup checked — rotated side to side only where allowed.")
    e2 = entry(rc, 210, kind="repair", type_key="brake_pads", mileage=43120,
               description="Front brake pads replaced — squealing under light braking",
               parts_used="Front ceramic pads, brake lubricant", cost_cents=38450, performed_by="shop",
               shop_name="Lakeside Auto Care", notes="Rotors measured within spec; resurfacing not needed.")
    _attach(db, owner, rc, e2, "photo", _illustration("brake", "Old pads (before)", (120, 120, 120)), "Before")
    _attach(db, owner, rc, e2, "photo", _illustration("brake", "New pads (after)", (210, 80, 40)), "After")
    entry(rc, 400, kind="maintenance", type_key="cabin_filter", mileage=39800, description="Cabin air filter",
          cost_cents=2499, performed_by="diy")
    reminder(rc, type_key="oil_change", title="Oil change", interval_miles=5000, interval_months=6,
             last_done_date=today - timedelta(days=35), last_done_mileage=47950)
    reminder(rc, type_key="tire_rotation", title="Tire rotation", interval_miles=5000, interval_months=6,
             last_done_date=today - timedelta(days=35), last_done_mileage=47950)
    reminder(rc, type_key="brake_fluid", title="Brake fluid flush", interval_months=36,
             last_done_date=today - timedelta(days=1060), last_done_mileage=21000)
    for d, m in ((400, 39800), (210, 43120), (35, 47950), (2, 48210)):
        odo(rc, d, m)
    part(rc, type_key="oil_filter", name="Oil filter (cartridge)", part_number="04152-YZZA1", brand="Lexus/Toyota OEM",
         notes="Sample data — verify fitment for your VIN before ordering.")
    part(rc, type_key="brake_pads", name="Front brake pads", part_number="", brand="", notes="Add the OEM number from your invoice.")

    # ---- Lexus IS 350 ----------------------------------------------------
    is350 = vehicle(kind="car", make="Lexus", model="IS 350", nickname="IS", current_mileage=61540)
    spec(is350, "engine", "3.5L V6")
    e3 = entry(is350, 60, kind="maintenance", type_key="oil_change", mileage=60800, description="Oil change at dealer",
               parts_used="Synthetic oil, filter", cost_cents=12900, performed_by="shop", shop_name="Lexus dealer service")
    _attach(db, owner, is350, e3, "receipt", _illustration("receipt", "Dealer invoice", (13, 148, 136)), "Invoice")
    e4 = entry(is350, 150, kind="maintenance", type_key="tire_replacement", mileage=58900,
               description="Four new all-season tires, mounted and balanced", cost_cents=124000,
               performed_by="shop", shop_name="Discount tire shop")
    _attach(db, owner, is350, e4, "photo", _illustration("tire", "New tires", (40, 40, 40)), "New tires")
    entry(is350, 151, kind="maintenance", type_key="wheel_alignment", mileage=58900, description="Four-wheel alignment",
          cost_cents=11999, performed_by="shop", shop_name="Discount tire shop")
    entry(is350, 500, kind="repair", type_key="battery", mileage=51000, description="Battery failed a load test; replaced",
          cost_cents=21995, performed_by="diy", notes="Old battery recycled at the parts store.")
    reminder(is350, type_key="oil_change", title="Oil change", interval_miles=5000, interval_months=6,
             last_done_date=today - timedelta(days=170), last_done_mileage=57200)
    odo(is350, 150, 58900)
    odo(is350, 60, 60800)
    odo(is350, 1, 61540)

    # ---- Lexus RC F ------------------------------------------------------
    rcf = vehicle(kind="car", make="Lexus", model="RC F", nickname="RC F", current_mileage=22870)
    spec(rcf, "engine", "5.0L V8")
    spec(rcf, "cylinders", "8")
    entry(rcf, 20, kind="maintenance", type_key="detailing", mileage=22850, description="Hand wash, clay bar and wax",
          cost_cents=0, performed_by="diy")
    entry(rcf, 180, kind="maintenance", type_key="oil_change", mileage=21100, description="Oil change", cost_cents=14500,
          performed_by="shop", shop_name="Lexus dealer service")
    entry(rcf, 320, kind="maintenance", type_key="registration", description="Annual registration renewal", cost_cents=18700,
          performed_by="diy")
    reminder(rcf, type_key="oil_change", title="Oil change", interval_miles=5000, interval_months=6,
             last_done_date=today - timedelta(days=180), last_done_mileage=21100)
    reminder(rcf, type_key="registration", title="Registration renewal", interval_months=12,
             last_done_date=today - timedelta(days=320))
    odo(rcf, 180, 21100)
    odo(rcf, 20, 22850)

    # ---- 2014 Kia Forte ----------------------------------------------------
    forte = vehicle(kind="car", year=2014, make="Kia", model="Forte", nickname="Forte", current_mileage=118400)
    entry(forte, 15, kind="maintenance", type_key="oil_change", mileage=118300, description="Oil and filter",
          cost_cents=4999, performed_by="diy")
    e5 = entry(forte, 90, kind="repair", type_key="battery", mileage=116900, description="No-start in cold weather; new battery",
               cost_cents=17999, performed_by="diy")
    _attach(db, owner, forte, e5, "photo", _illustration("battery", "New battery installed", (234, 88, 12)), "New battery")
    entry(forte, 240, kind="maintenance", type_key="wipers", mileage=114000, description="Front wiper blades", cost_cents=3598,
          performed_by="diy")
    entry(forte, 365, kind="maintenance", type_key="inspection", mileage=112500, description="State safety inspection — passed",
          cost_cents=3500, performed_by="shop", shop_name="Corner Garage")
    reminder(forte, type_key="oil_change", title="Oil change", interval_miles=5000, interval_months=6,
             last_done_date=today - timedelta(days=15), last_done_mileage=118300)
    reminder(forte, type_key="inspection", title="State inspection", interval_months=12,
             last_done_date=today - timedelta(days=365))
    odo(forte, 90, 116900)
    odo(forte, 15, 118300)

    # ---- Ducati Monster 696 -------------------------------------------------
    duc = vehicle(kind="motorcycle", make="Ducati", model="Monster 696", nickname="Monster", current_mileage=14250)
    spec(duc, "displacement_l", "0.696")
    spec(duc, "cylinders", "2")
    e6 = entry(duc, 10, kind="maintenance", type_key="chain_lube", mileage=14240, description="Cleaned and lubed chain",
               parts_used="Chain cleaner, chain lube", cost_cents=0, performed_by="diy")
    _attach(db, owner, duc, e6, "photo", _illustration("chain", "Clean chain", (90, 90, 95)), "Clean chain")
    entry(duc, 10, kind="maintenance", type_key="chain_adjustment", mileage=14240, description="Adjusted chain slack",
          performed_by="diy")
    entry(duc, 300, kind="maintenance", type_key="valve_adjustment", mileage=12000,
          description="Desmo service: valve clearance check and timing belts", cost_cents=89000, performed_by="shop",
          shop_name="Ducati service center")
    entry(duc, 300, kind="maintenance", type_key="timing_belt", mileage=12000, description="Timing belts replaced",
          performed_by="shop", shop_name="Ducati service center")
    reminder(duc, type_key="chain_lube", title="Chain clean & lube", interval_miles=500,
             last_done_date=today - timedelta(days=10), last_done_mileage=14240)
    reminder(duc, type_key="oil_change", title="Oil change", interval_miles=4500, interval_months=12,
             last_done_date=today - timedelta(days=300), last_done_mileage=12000)
    reminder(duc, type_key="timing_belt", title="Timing belts", interval_months=24,
             last_done_date=today - timedelta(days=300), last_done_mileage=12000)
    odo(duc, 300, 12000)
    odo(duc, 10, 14240)

    db.commit()
    log.info("Sample data added for user '%s'.", owner.username)
