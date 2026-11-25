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
    v = make_vehicle(client, admin)
    r = client.post(f"/api/vehicles/{v['id']}/carfax/parse", headers=admin,
                    files={"file": ("carfax.pdf", _carfax_pdf(), "application/pdf")})
    assert r.status_code == 200, r.text
    rows = r.json()["entries"]
    assert len(rows) == 4
    assert rows[0]["date"] == "2019-03-15" and rows[0]["mileage"] == 12345
    assert rows[0]["type_key"] == "oil_change" and rows[0]["suggested"]
    assert rows[1]["type_key"] == "brake_pads" and rows[1]["kind"] == "repair"
    assert rows[2]["suggested"] is False  # title record
    assert rows[3]["type_key"] == "tire_replacement"
    approved = [{k: row[k] for k in ("date", "mileage", "type_key", "kind", "description", "shop_name", "notes")}
                for row in rows if row["suggested"]]
    r = client.post(f"/api/vehicles/{v['id']}/carfax/apply", headers=admin, json={"entries": approved})
    assert r.json()["created"] == 3
    entries = client.get(f"/api/entries?vehicle_id={v['id']}", headers=admin).json()
    assert {e["source"] for e in entries} == {"carfax"}


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
