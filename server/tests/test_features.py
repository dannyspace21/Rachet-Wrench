"""
Feature tests: vehicles, entries, reminders, odometer, parts, sync, VIN
decoding, door-sticker parsing, Carfax import and the history report.
"""

import io
import json
from datetime import date, timedelta

import pytest
from conftest import make_vehicle
from fpdf import FPDF
from PIL import Image, ImageDraw, ImageFont
from pypdf import PdfReader

# ---------------------------------------------------------------------------
# Records & reminders
# ---------------------------------------------------------------------------


def test_vehicle_entry_flow_updates_mileage_and_reminders(client, admin):
    v = make_vehicle(client, admin, current_mileage=1000)
    rem = client.post("/api/reminders", headers=admin, json={
        "vehicle_id": v["id"], "type_key": "oil_change", "interval_miles": 5000, "interval_months": 6,
        "last_done_date": (date.today() - timedelta(days=400)).isoformat(), "last_done_mileage": 0}).json()
    assert rem["due"]["status"] == "overdue"  # 6 months passed
    e = client.post("/api/entries", headers=admin, json={
        "vehicle_id": v["id"], "kind": "maintenance", "type_key": "oil_change", "date": date.today().isoformat(),
        "mileage": 1500, "cost_cents": 4999, "performed_by": "diy", "description": "Oil change"})
    assert e.status_code == 201
    assert client.get(f"/api/vehicles/{v['id']}", headers=admin).json()["current_mileage"] == 1500
    rem = client.get(f"/api/reminders/{rem['id']}", headers=admin).json()
    assert rem["last_done_mileage"] == 1500
    assert rem["due"]["status"] == "ok" and rem["due"]["due_mileage"] == 6500


def test_repair_entry_also_marks_reminder_done(client, admin):
    """A tire rotation logged as a repair (not maintenance) still updates the reminder."""
    v = make_vehicle(client, admin, current_mileage=40000)
    rem = client.post("/api/reminders", headers=admin, json={
        "vehicle_id": v["id"], "type_key": "tire_rotation", "interval_miles": 5000, "interval_months": 6,
        "last_done_date": (date.today() - timedelta(days=30)).isoformat(), "last_done_mileage": 30000}).json()
    assert rem["due"]["status"] == "overdue"  # miles side
    client.post("/api/entries", headers=admin, json={
        "vehicle_id": v["id"], "kind": "repair", "type_key": "tire_rotation", "date": date.today().isoformat(),
        "mileage": 40100})
    rem = client.get(f"/api/reminders/{rem['id']}", headers=admin).json()
    assert rem["last_done_mileage"] == 40100 and rem["due"]["status"] == "ok"


def test_marking_reminder_done_by_editing_date_and_mileage(client, admin):
    """Updating last-done date AND mileage clears an overdue reminder."""
    v = make_vehicle(client, admin, current_mileage=40000)
    rem = client.post("/api/reminders", headers=admin, json={
        "vehicle_id": v["id"], "type_key": "oil_change", "interval_miles": 5000, "interval_months": 6,
        "last_done_date": "2024-01-01", "last_done_mileage": 20000}).json()
    assert rem["due"]["status"] == "overdue"
    rem = client.patch(f"/api/reminders/{rem['id']}", headers=admin, json={
        "last_done_date": date.today().isoformat(), "last_done_mileage": 40000}).json()
    assert rem["due"]["status"] == "ok"


def test_reminder_whichever_first():
    from app.models import Reminder
    from app.reminders import add_months, reminder_status

    r = Reminder(interval_miles=5000, interval_months=6, last_done_mileage=10000,
                 last_done_date=date(2025, 1, 31), enabled=True)
    assert add_months(date(2025, 1, 31), 1) == date(2025, 2, 28)
    assert reminder_status(r, 14800, date(2025, 3, 1))["status"] == "due_soon"  # 200 mi left
    assert reminder_status(r, 11000, date(2025, 7, 31))["status"] == "overdue"  # date reached first
    assert reminder_status(r, 15000, date(2025, 2, 1))["status"] == "overdue"  # miles reached first
    assert reminder_status(r, 11000, date(2025, 2, 1))["status"] == "ok"


def test_unknown_type_rejected(client, admin):
    v = make_vehicle(client, admin)
    r = client.post("/api/entries", headers=admin, json={"vehicle_id": v["id"], "date": "2025-01-01", "type_key": "rm -rf"})
    assert r.status_code == 422


def test_soft_delete_cascades_and_syncs(client, admin):
    v = make_vehicle(client, admin)
    e = client.post("/api/entries", headers=admin, json={"vehicle_id": v["id"], "date": "2025-01-01"}).json()
    first = client.get("/api/sync", headers=admin).json()
    assert client.delete(f"/api/vehicles/{v['id']}", headers=admin).status_code == 200
    assert client.get("/api/vehicles", headers=admin).json() == []
    changes = client.get(f"/api/sync?since={first['next_since']}", headers=admin).json()
    assert changes["vehicles"][0]["deleted_at"] is not None
    assert changes["entries"][0]["id"] == e["id"] and changes["entries"][0]["deleted_at"] is not None


def test_sync_push_offline_records_and_last_write_wins(client, admin):
    vid = "11111111-1111-4111-8111-111111111111"
    eid = "22222222-2222-4222-8222-222222222222"
    body = {
        "vehicles": [{"id": vid, "make": "Ducati", "model": "Monster 696", "kind": "motorcycle", "updated_at": "2025-05-01T10:00:00Z"}],
        "entries": [{"id": eid, "vehicle_id": vid, "date": "2025-05-01", "type_key": "chain_lube", "updated_at": "2025-05-01T10:00:00Z"}],
    }
    r = client.post("/api/sync", headers=admin, json=body).json()
    assert r["results"]["vehicles"][0]["status"] == "applied"
    assert r["results"]["entries"][0]["status"] == "applied"
    # An OLDER edit from another device loses.
    old = {"vehicles": [{"id": vid, "nickname": "old", "updated_at": "2025-04-01T00:00:00Z"}]}
    assert client.post("/api/sync", headers=admin, json=old).json()["results"]["vehicles"][0]["status"] == "kept_server_copy"
    # A NEWER edit wins.
    new = {"vehicles": [{"id": vid, "nickname": "Monster", "updated_at": "2025-06-01T00:00:00Z"}]}
    assert client.post("/api/sync", headers=admin, json=new).json()["results"]["vehicles"][0]["status"] == "applied"
    assert client.get(f"/api/vehicles/{vid}", headers=admin).json()["nickname"] == "Monster"


def test_specs_are_unique_per_field_and_labelled(client, admin):
    v = make_vehicle(client, admin)
    client.post("/api/specs", headers=admin, json={"vehicle_id": v["id"], "key": "oil_type", "value": "0W-20", "source": "user"})
    client.post("/api/specs", headers=admin, json={"vehicle_id": v["id"], "key": "oil_type", "value": "5W-30", "source": "user"})
    specs = client.get(f"/api/specs?vehicle_id={v['id']}", headers=admin).json()
    assert len(specs) == 1 and specs[0]["value"] == "5W-30"
    table = client.get(f"/api/vehicles/{v['id']}/specs-table", headers=admin).json()
    assert any(r["key"] == "tire_size_front" and r["value"] == "" for r in table)  # unknown stays blank
    r = client.post("/api/specs", headers=admin, json={"vehicle_id": v["id"], "key": "oil_type", "value": "x", "source": "guess"})
    assert r.status_code == 422


def test_manufacturer_links(client, admin):
    v = make_vehicle(client, admin, make="Lexus")
    links = client.get(f"/api/vehicles/{v['id']}/links", headers=admin).json()
    assert links["owners"].startswith("https://") and links["known_make"]
    client.patch(f"/api/vehicles/{v['id']}", headers=admin, json={"parts_url": "https://example.com/parts"})
    assert client.get(f"/api/vehicles/{v['id']}/links", headers=admin).json()["parts"] == "https://example.com/parts"


# ---------------------------------------------------------------------------
# VIN
# ---------------------------------------------------------------------------


def test_vin_check_digit():
    from app.vin import is_valid_vin, normalize_vin

    assert is_valid_vin("1HGCM82633A004352")  # widely published example VIN
    assert not is_valid_vin("1HGCM82633A004353")
    assert normalize_vin("1hgcm8263 3a0O4352") == "1HGCM82633A004352"
    assert not is_valid_vin("1HGCM82633A00435")  # too short


NHTSA_SAMPLE = {"Results": [{
    "ModelYear": "2014", "Make": "KIA", "Model": "Forte", "Trim": "", "VehicleType": "PASSENGER CAR",
    "DisplacementL": "1.8", "EngineCylinders": "4", "EngineConfiguration": "In-Line", "FuelTypePrimary": "Gasoline",
    "BodyClass": "Sedan/Saloon", "DriveType": "FWD/Front-Wheel Drive", "ErrorCode": "0", "ErrorText": "",
}]}


def test_vin_decode_endpoint(client, admin, monkeypatch):
    from app import vin as vin_mod

    class FakeResp:
        def __init__(self, body):
            self.body = body

        def read(self, n=-1):
            return self.body

        def __enter__(self):
            return self

        def __exit__(self, *a):
            return False

    calls = []

    def fake_urlopen(req, timeout):
        calls.append(req.full_url)
        return FakeResp(json.dumps(NHTSA_SAMPLE).encode())

    monkeypatch.setattr(vin_mod.urllib.request, "urlopen", fake_urlopen)
    r = client.post("/api/vin/decode", headers=admin, json={"vin": "1HGCM82633A004352"})
    assert r.status_code == 200, r.text
    body = r.json()
    assert calls[0].startswith("https://vpic.nhtsa.dot.gov/")
    assert body["vehicle"] == {"year": 2014, "make": "Kia", "model": "Forte", "kind": "car"}
    specs = {s["key"]: s for s in body["specs"]}
    assert specs["engine"]["value"].startswith("1.8L") and specs["engine"]["source"] == "vin"
    assert "oil_type" not in specs  # never invented
    assert client.post("/api/vin/decode", headers=admin, json={"vin": "NOTAVIN12345"}).status_code == 400


# ---------------------------------------------------------------------------
# Door sticker
# ---------------------------------------------------------------------------
STICKER_TEXT = """
TIRE AND LOADING INFORMATION
SEATING CAPACITY TOTAL 4 FRONT 2 REAR 2
The combined weight of occupants and cargo should never exceed 340 kg or 750 lbs.
TIRE    SIZE              COLD TIRE PRESSURE
FRONT   235/40R19 96W     240 KPA, 35 PSI
REAR    265/35R19 98W     240 KPA, 35 PSI
SPARE   T145/70D18        420 KPA, 60 PSI
MFD BY TOYOTA MOTOR CORPORATION 04/15
GVWR 2,250 KG (4,960 LB)
GAWR FRONT 1,125 KG (2,480 LB)  GAWR REAR 1,175 KG (2,590 LB)
VIN 1HGCM82633A004352
C/TR: 223/LA20
"""


def test_sticker_parser_extracts_fields():
    from app.sticker_parser import parse_sticker_text

    f = parse_sticker_text(STICKER_TEXT)
    assert f["vin"] == "1HGCM82633A004352"
    assert f["tire_size_front"] == "235/40R19 96W"
    assert f["tire_size_rear"] == "265/35R19 98W"
    assert f["tire_size_spare"] == "T145/70D18"
    assert (f["tire_pressure_front"], f["tire_pressure_rear"], f["tire_pressure_spare"]) == ("35", "35", "60")
    assert f["rim_size_front"] == "19"
    assert f["gvwr"] == "4960" and f["gawr_front"] == "2480" and f["gawr_rear"] == "2590"
    assert f["paint_code"] == "223" and f["trim_code"] == "LA20"
    assert f["build_date"] == "04/15"


def test_sticker_parser_does_not_guess():
    from app.sticker_parser import parse_sticker_text

    assert parse_sticker_text("") == {}
    assert parse_sticker_text("hello world, nothing to see") == {}
    # A VIN with a wrong check digit is not offered.
    assert "vin" not in parse_sticker_text("VIN 1HGCM82633A004353")


def _sticker_png() -> bytes:
    img = Image.new("RGB", (1500, 700), "white")
    d = ImageDraw.Draw(img)
    font = ImageFont.load_default(size=44)
    lines = ["TIRE AND LOADING INFORMATION", "FRONT 235/40R19 96W 35 PSI", "REAR 265/35R19 98W 35 PSI",
             "GVWR 4960 LB", "VIN 1HGCM82633A004352"]
    for i, line in enumerate(lines):
        d.text((40, 40 + i * 120), line, fill="black", font=font)
    buf = io.BytesIO()
    img.save(buf, "PNG")
    return buf.getvalue()


def test_sticker_scan_and_apply(client, admin):
    from app.ocr import tesseract_available

    if not tesseract_available():
        pytest.skip("tesseract not installed")
    v = make_vehicle(client, admin)
    r = client.post(f"/api/vehicles/{v['id']}/sticker/scan", headers=admin,
                    files=[("files", ("sticker.png", _sticker_png(), "image/png"))])
    assert r.status_code == 200, r.text
    fields = {f["key"]: f["value"] for f in r.json()["fields"]}
    assert fields.get("tire_size_front") == "235/40R19 96W"
    assert fields.get("vin") == "1HGCM82633A004352"
    # Photo saved to the vehicle as a sticker attachment.
    atts = client.get(f"/api/attachments?vehicle_id={v['id']}", headers=admin).json()
    assert atts[0]["category"] == "sticker"
    # Apply (user approved two fields, edited one).
    r = client.post(f"/api/vehicles/{v['id']}/sticker/apply", headers=admin, json={"fields": [
        {"key": "tire_size_front", "value": "235/40R19 96W"}, {"key": "tire_pressure_front", "value": "36"},
        {"key": "vin", "value": "1HGCM82633A004352"}]})
    assert r.status_code == 200
    specs = {s["key"]: s for s in client.get(f"/api/specs?vehicle_id={v['id']}", headers=admin).json()}
    assert specs["tire_pressure_front"]["value"] == "36" and specs["tire_pressure_front"]["source"] == "sticker"
    assert client.get(f"/api/vehicles/{v['id']}", headers=admin).json()["vin"] == "1HGCM82633A004352"


def test_ai_vision_refused_when_disabled(client, admin):
    v = make_vehicle(client, admin)
    r = client.post(f"/api/vehicles/{v['id']}/sticker/scan", headers=admin, data={"use_ai": "true"},
                    files=[("files", ("sticker.png", _sticker_png(), "image/png"))])
    assert r.status_code == 400


# ---------------------------------------------------------------------------
# Carfax
# ---------------------------------------------------------------------------


def _carfax_pdf() -> bytes:
    pdf = FPDF()
    pdf.add_page()
    pdf.set_font("Helvetica", size=10)
    lines = [
        "CARFAX Vehicle History Report", "Detailed History",
        "Date Mileage Source Comments",
        "03/15/2019 12,345 Lakeside Auto Care", "- Vehicle serviced", "- Oil and filter changed", "- Tires rotated",
        "07/02/2020 24,100 Corner Garage", "- Brake pads replaced",
        "09/09/2021 Texas Motor Vehicle Dept.", "- Title issued or updated",
        "01/20/2022 33,800 Tire Shop", "- Four tires replaced", "- Alignment performed",
    ]
    for line in lines:
        pdf.cell(0, 6, line, new_x="LMARGIN", new_y="NEXT")
    return bytes(pdf.output())


def test_carfax_parse_review_and_apply(client, admin):
    """Classic Vehicle History Report: one entry per kind of work."""
    v = make_vehicle(client, admin)
    r = client.post(f"/api/vehicles/{v['id']}/carfax/parse", headers=admin,
                    files={"file": ("carfax.pdf", _carfax_pdf(), "application/pdf")})
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["format"] == "history_report"
    rows = body["entries"]
    by = {(row["date"], row["type_key"]): row for row in rows}
    # "Vehicle serviced" is dropped because the visit lists the real work.
    assert ("2019-03-15", "oil_change") in by and ("2019-03-15", "tire_rotation") in by
    assert ("2019-03-15", "scheduled_service") not in by
    assert by[("2019-03-15", "oil_change")]["mileage"] == 12345
    assert by[("2020-07-02", "brake_pads")]["kind"] == "maintenance"  # pads are a wear item
    assert by[("2021-09-09", "registration")]["suggested"] is False   # title record: paperwork
    assert by[("2022-01-20", "tire_replacement")]["suggested"]
    assert by[("2022-01-20", "wheel_alignment")]["suggested"]
    approved = [{k: row[k] for k in ("date", "mileage", "type_key", "custom_type", "kind", "description",
                                     "shop_name", "performed_by", "notes")}
                for row in rows if row["suggested"]]
    r = client.post(f"/api/vehicles/{v['id']}/carfax/apply", headers=admin, json={"entries": approved})
    assert r.json()["created"] == len(approved)
    entries = client.get(f"/api/entries?vehicle_id={v['id']}", headers=admin).json()
    assert {e["source"] for e in entries} == {"carfax"}


def _car_care_pdf() -> bytes:
    """A two-column "Car Care" service history, laid out like the printed
    carfax.com page: a summary list on the left, visit cards on the right."""
    cards = [
        ("Self-Service (DIY)", "10/17/2026", "175,000 mi", ["Air filter replaced"], "Add Note"),
        ("Alloy Wheel Solution Inc", "06/08/2026", "175,000 mi", ["Wheel(s) repaired"], "Edit Note"),
        ("Hogan & Sons Tire & Auto (https://www.carfax.com/Reviews-Hogan-|And-Sons-Tire-And-Auto-Herndon-VA?intp=cc)",
         "06/01/2026", "175,000 mi", ["Vehicle serviced", "Maintenance inspection completed", "Alignment performed"], "Edit Note"),
        ("Self-Service (DIY)", "05/25/2026", "172,749 mi",
         ["Control arm bushing(s) replaced", "Rear shock absorber(s) replaced"], "Add Note"),
        ("Self-Service (DIY)", "04/04/2026", "172,000 mi",
         ["Tires rotated", "Cabin air filter replaced/cleaned", "Oil and filter changed"], "Add Note"),
        ("Virginia Motor Vehicle Dept.", "04/02/2026", "171,865 mi", ["Title issued or updated", "New owner reported"], "Add Note"),
        ("Lexus of Lehigh Valley", "10/10/2017", None, ["Vehicle serviced", "Oil and filter changed"], "Add Note"),
    ]
    pdf = FPDF(unit="pt", format="letter")
    pdf.set_auto_page_break(False)
    pdf.add_page()
    pdf.set_font("Helvetica", size=9)
    # Left column: the summary list (dates, mileage and shops — must be ignored).
    y = 60
    pdf.text(30, y, "Date")
    pdf.text(200, y, "Odometer")
    for shop, d, odo, _s, _n in cards:
        y += 26
        pdf.text(30, y, d)
        if odo:
            pdf.text(200, y, odo.replace(" ", ""))
        pdf.text(30, y + 11, shop.split(" (")[0])
    # Right column: the cards.
    y = 60
    for shop, d, odo, services, note in cards:
        if y > 640:
            pdf.add_page()
            y = 50
        for part in shop.split("|"):
            pdf.text(254, y, part)
            y += 12
        y += 10
        pdf.text(254, y, "Date")
        if odo:
            pdf.text(404, y, "Odometer")
        y += 14
        pdf.text(254, y, d)
        if odo:
            pdf.text(404, y, odo)
        y += 20
        pdf.text(254, y, "Services Performed")
        for i, line in enumerate(services):
            y += 12
            pdf.text(254, y, line)
            if i == len(services) - 1:
                pdf.text(512, y, "Edit Record")
        y += 30
        pdf.text(268, y, "Upload Receipts")
        y += 20
        pdf.text(268, y, note)
        y += 40
    return bytes(pdf.output())


def test_carfax_car_care_service_history(client, admin):
    """The printed carfax.com "Service History" page (two columns)."""
    v = make_vehicle(client, admin)
    r = client.post(f"/api/vehicles/{v['id']}/carfax/parse", headers=admin,
                    files={"file": ("carfax.pdf", _car_care_pdf(), "application/pdf")})
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["format"] == "car_care"
    assert body["visits"] == 7                      # the left-hand summary list adds nothing
    assert body["newest_mileage"] == 175000
    rows = body["entries"]
    by = {(row["date"], row["type_key"]): row for row in rows}

    air = by[("2026-10-17", "air_filter")]
    assert air["performed_by"] == "diy" and air["shop_name"] == "" and air["mileage"] == 175000
    assert air["description"] == "Air filter replaced" and air["kind"] == "maintenance"

    wheel = by[("2026-06-08", "wheel_repair")]
    assert wheel["kind"] == "repair" and wheel["shop_name"] == "Alloy Wheel Solution Inc"

    hogan = by[("2026-06-01", "wheel_alignment")]
    assert hogan["shop_name"] == "Hogan & Sons Tire & Auto"   # the review link is removed
    assert ("2026-06-01", "inspection") in by
    assert ("2026-06-01", "scheduled_service") not in by      # "Vehicle serviced" dropped

    assert by[("2026-05-25", "suspension")]["kind"] == "repair"
    assert by[("2026-05-25", "shocks_struts")]["kind"] == "repair"
    assert by[("2026-05-25", "shocks_struts")]["performed_by"] == "diy"
    for key in ("tire_rotation", "cabin_filter", "oil_change"):
        assert by[("2026-04-04", key)]["mileage"] == 172000
    assert by[("2026-04-02", "registration")]["suggested"] is False
    assert by[("2017-10-10", "oil_change")]["mileage"] is None   # Carfax had no odometer for it

    approved = [{k: row[k] for k in ("date", "mileage", "type_key", "custom_type", "kind", "description",
                                     "shop_name", "performed_by", "notes")}
                for row in rows if row["suggested"]]
    r = client.post(f"/api/vehicles/{v['id']}/carfax/apply", headers=admin, json={"entries": approved})
    assert r.status_code == 200, r.text
    res = r.json()
    assert res["created"] == len(approved)
    assert res["current_mileage"] == 175000                    # odometer brought up to date
    assert res["odometer_readings_added"] >= 5
    assert "Oil change" in res["reminders_created"] and "Engine air filter" in res["reminders_created"]
    assert "Inspection" not in res["reminders_created"]        # routine checks only: no yearly reminder
    rems = {x["type_key"]: x for x in client.get(f"/api/reminders?vehicle_id={v['id']}", headers=admin).json()}
    assert rems["oil_change"]["last_done_date"] == "2026-04-04"  # the NEWEST oil change
    assert rems["oil_change"]["last_done_mileage"] == 172000
    assert rems["air_filter"]["last_done_date"] == "2026-10-17"
    assert "suspension" not in rems                            # repairs don't get reminders

    # Importing again: everything is recognised as already there.
    again = client.post(f"/api/vehicles/{v['id']}/carfax/parse", headers=admin,
                        files={"file": ("carfax.pdf", _car_care_pdf(), "application/pdf")}).json()
    assert again["previous_imports"] == len(approved)
    assert all(row["duplicate"] for row in again["entries"] if row["suggested"])
    r = client.post(f"/api/vehicles/{v['id']}/carfax/apply", headers=admin, json={"entries": approved})
    assert r.json()["created"] == 0 and r.json()["skipped_duplicates"] == len(approved)
    assert r.json()["reminders_created"] == []                 # existing reminders are reused

    # Redoing a bad import: earlier Carfax entries are replaced, not doubled.
    r = client.post(f"/api/vehicles/{v['id']}/carfax/apply", headers=admin,
                    json={"entries": approved, "replace_previous": True})
    assert r.json()["removed_previous"] == len(approved) and r.json()["created"] == len(approved)
    assert len(client.get(f"/api/entries?vehicle_id={v['id']}", headers=admin).json()) == len(approved)


def test_carfax_rejects_non_pdf(client, admin):
    v = make_vehicle(client, admin)
    r = client.post(f"/api/vehicles/{v['id']}/carfax/parse", headers=admin, files={"file": ("x.pdf", b"hello", "application/pdf")})
    assert r.status_code == 415


# ---------------------------------------------------------------------------
# Report
# ---------------------------------------------------------------------------


def test_history_report_pdf(client, admin):
    v = make_vehicle(client, admin, current_mileage=48000, vin="1HGCM82633A004352")
    client.post("/api/specs", headers=admin, json={"vehicle_id": v["id"], "key": "oil_type", "value": "0W-20 — synthetic"})
    e = client.post("/api/entries", headers=admin, json={"vehicle_id": v["id"], "date": "2025-03-01", "type_key": "brake_pads",
                                                         "kind": "repair", "cost_cents": 38450, "description": "Front pads"}).json()
    buf = io.BytesIO()
    Image.new("RGB", (800, 600), (200, 100, 30)).save(buf, "JPEG")
    client.post("/api/attachments", headers=admin, data={"vehicle_id": v["id"], "entry_id": e["id"]},
                files={"file": ("p.jpg", buf.getvalue(), "image/jpeg")})
    r = client.get(f"/api/vehicles/{v['id']}/report.pdf", headers=admin)
    assert r.status_code == 200 and r.content.startswith(b"%PDF")
    text = "".join(p.extract_text() for p in PdfReader(io.BytesIO(r.content)).pages)
    assert "Vehicle History Report" in text and "Brake pads" in text and "$384.50" in text
    assert "From VIN" not in text and "Entered by me" in text


def test_seed_data_loads(client):
    from app.database import session_scope
    from app.models import Vehicle
    from app.seed import seed_sample_data

    with session_scope() as db:
        seed_sample_data(db)
        names = sorted(v.model for v in db.query(Vehicle).all())
    assert names == ["Forte", "IS 350", "Monster 696", "RC 350", "RC F"]


# ---------------------------------------------------------------------------
# Smart Shopping (catalog-driven; the search building itself is tested in
# web/tests/shopping.test.mjs and Android's ShoppingTest with shared cases)
# ---------------------------------------------------------------------------
def test_catalog_serves_smart_shopping_config(client, admin):
    cat = client.get("/api/catalog", headers=admin).json()
    retailers = {r["key"]: r for r in cat["retailers"]}
    assert set(retailers) == {"amazon", "walmart", "advance_auto"}
    for r in retailers.values():
        assert r["name"] and r["enabled"] is True
        assert r["search_url"].startswith("https://") and "{q}" in r["search_url"]
        assert r["affiliate"]["enabled"] is False and not r["affiliate"]["wrap"]  # no affiliate tracking configured
    types = {t["key"] for t in cat["types"]}
    specs = {s["key"] for s in cat["specs"]}
    assert "oil_change" in cat["shopping"] and "inspection" not in cat["shopping"]
    import re
    for type_key, items in cat["shopping"].items():
        assert type_key in types
        for item in items:
            for q in item["queries"]:
                for kind, key in re.findall(r"\{(spec|part):([a-z_]+)", q):
                    assert key in (specs if kind == "spec" else types), (type_key, q)


def test_web_shopping_logic_with_shared_cases():
    """Runs the web app's Smart Shopping tests (node) when Node.js is available."""
    import shutil
    import subprocess
    from pathlib import Path

    import pytest

    node = shutil.which("node")
    if not node:
        pytest.skip("Node.js not installed")
    root = Path(__file__).resolve().parents[2]
    tests = sorted(str(p) for p in (root / "web" / "tests").glob("*.test.mjs"))
    # Reviewed: fixed program + our own test files, no shell.
    r = subprocess.run([node, "--test", *tests], cwd=root, capture_output=True, text=True, timeout=120, check=False)  # noqa: S603  # nosemgrep
    assert r.returncode == 0, r.stdout[-3000:] + r.stderr[-2000:]


def test_carfax_recognises_oil_change_wordings():
    """Different ways shops write an oil change are all oil changes (never air filter)."""
    from app.carfax import classify

    for line in ("Oil and filter changed", "Oil & filter changed", "Changed oil & filter", "Oil/filter service",
                 "Lube, oil and filter", "Oil changed"):
        assert classify(line)[0] == "oil_change", line
    assert classify("Engine air filter replaced")[0] == "air_filter"
    assert classify("Oil filter replaced")[0] == "oil_filter"
