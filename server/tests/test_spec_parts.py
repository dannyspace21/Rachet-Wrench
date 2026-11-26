"""Specs that are also parts: filling them in adds/updates the part on the Parts tab."""

from conftest import make_vehicle

from app.spec_parts import spec_part_id


def _parts(client, h, v):
    return {p["name"]: p for p in client.get(f"/api/parts?vehicle_id={v['id']}", headers=h).json()}


def test_id_matches_java_name_uuid():
    # Same value Android computes with UUID.nameUUIDFromBytes (see SpecPartsTest.kt).
    assert spec_part_id("11111111-2222-4333-8444-555555555555", "oil_filter_part") == "bbdf75ad-064d-3dd7-9a2b-7ded63f937fd"


def test_spec_adds_updates_and_removes_part(client, admin):
    v = make_vehicle(client, admin)
    s = client.post("/api/specs", headers=admin, json={"vehicle_id": v["id"], "key": "oil_filter_part", "value": "04152-YZZA1"}).json()
    client.post("/api/specs", headers=admin, json={"vehicle_id": v["id"], "key": "oil_type", "value": "0W-20 full synthetic"})
    client.post("/api/specs", headers=admin, json={"vehicle_id": v["id"], "key": "battery_group", "value": "24F"})
    client.post("/api/specs", headers=admin, json={"vehicle_id": v["id"], "key": "paint_code", "value": "223"})  # not a part
    parts = _parts(client, admin, v)
    assert parts["Oil filter"]["part_number"] == "04152-YZZA1" and parts["Oil filter"]["type_key"] == "oil_filter"
    assert parts["Engine oil"]["part_number"] == "0W-20 full synthetic"
    assert parts["Battery"]["part_number"] == "Group 24F"
    assert len(parts) == 3
    assert parts["Oil filter"]["id"] == spec_part_id(v["id"], "oil_filter_part")

    # Changing the spec updates the part (no duplicate).
    client.patch(f"/api/specs/{s['id']}", headers=admin, json={"value": "90915-YZZD1"})
    parts = _parts(client, admin, v)
    assert parts["Oil filter"]["part_number"] == "90915-YZZD1" and len(parts) == 3

    # Deleting the spec removes its auto part.
    client.delete(f"/api/specs/{s['id']}", headers=admin)
    assert "Oil filter" not in _parts(client, admin, v)


def test_phone_and_server_agree_on_the_part(client, admin):
    """The phone adds the same part offline (same id): syncing it merges, never duplicates."""
    v = make_vehicle(client, admin)
    pid = spec_part_id(v["id"], "tire_size_rear")
    r = client.post("/api/sync", headers=admin, json={
        "specs": [{"id": "aaaaaaaa-2222-4333-8444-555555555555", "vehicle_id": v["id"], "key": "tire_size_rear",
                   "value": "255/35R19", "updated_at": "2026-11-01T00:00:00Z"}],
        "parts": [{"id": pid, "vehicle_id": v["id"], "type_key": "tire_replacement", "name": "Rear tires",
                   "part_number": "255/35R19", "notes": "Added from Specs", "updated_at": "2026-11-01T00:00:01Z"}]}).json()
    assert r["results"]["parts"][0]["status"] in ("applied", "kept_server_copy")  # same record either way
    parts = client.get(f"/api/parts?vehicle_id={v['id']}", headers=admin).json()
    assert [p["id"] for p in parts] == [pid]


def test_backfill_existing_specs(client, admin):
    from app.database import session_scope
    from app.models import Part
    from app.spec_parts import backfill

    v = make_vehicle(client, admin)
    client.post("/api/specs", headers=admin, json={"vehicle_id": v["id"], "key": "tire_size_front", "value": "235/40R19"})
    with session_scope() as db:  # pretend it was saved before this feature existed
        db.delete(db.get(Part, spec_part_id(v["id"], "tire_size_front")))
        db.commit()
        assert backfill(db) == 1
        assert backfill(db) == 0
    assert _parts(client, admin, v)["Front tires"]["part_number"] == "235/40R19"


def test_no_duplicate_of_a_part_saved_by_hand(client, admin):
    v = make_vehicle(client, admin)
    client.post("/api/parts", headers=admin, json={"vehicle_id": v["id"], "type_key": "oil_filter", "name": "Oil filter (cartridge)",
                                                     "part_number": "04152-YZZA1"})
    client.post("/api/specs", headers=admin, json={"vehicle_id": v["id"], "key": "oil_filter_part", "value": "04152-YZZA1"})
    assert len(client.get(f"/api/parts?vehicle_id={v['id']}", headers=admin).json()) == 1
