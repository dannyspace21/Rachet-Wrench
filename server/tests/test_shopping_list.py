"""
Shopping list: items from a reminder, from a saved part, or typed in; with
quantity, OEM part number, spec and notes; optionally tied to a vehicle;
synced; private to each user; never an expense and never marks
maintenance done.
"""

from datetime import date, timedelta

from conftest import make_user, make_vehicle


def _reminder(client, h, v):
    return client.post("/api/reminders", headers=h, json={
        "vehicle_id": v["id"], "type_key": "oil_change", "interval_miles": 5000,
        "last_done_date": (date.today() - timedelta(days=400)).isoformat(), "last_done_mileage": 0}).json()


def test_add_from_reminder_part_and_manual(client, admin):
    v = make_vehicle(client, admin, current_mileage=10000)
    rem = _reminder(client, admin, v)
    part = client.post("/api/parts", headers=admin, json={"vehicle_id": v["id"], "type_key": "oil_filter",
                                                          "name": "Oil filter", "part_number": "04152-YZZA1"}).json()
    a = client.post("/api/shopping", headers=admin, json={"vehicle_id": v["id"], "reminder_id": rem["id"], "type_key": "oil_change",
                                                          "description": "Engine oil", "spec": "0W-20 full synthetic", "quantity": 5})
    b = client.post("/api/shopping", headers=admin, json={"vehicle_id": v["id"], "part_id": part["id"], "type_key": "oil_filter",
                                                          "description": "Oil filter", "part_number": "04152-YZZA1"})
    c = client.post("/api/shopping", headers=admin, json={"description": "Shop towels", "notes": "the blue ones"})
    assert a.status_code == b.status_code == c.status_code == 201
    assert a.json()["quantity"] == 5 and a.json()["reminder_id"] == rem["id"]
    assert b.json()["part_number"] == "04152-YZZA1" and b.json()["quantity"] == 1
    assert c.json()["vehicle_id"] is None  # a general item
    assert len(client.get("/api/shopping", headers=admin).json()) == 3
    assert len(client.get(f"/api/shopping?vehicle_id={v['id']}", headers=admin).json()) == 2

    # Bought: tick it off (moves to the end), then remove it.
    client.patch(f"/api/shopping/{a.json()['id']}", headers=admin, json={"purchased": True})
    items = client.get("/api/shopping", headers=admin).json()
    assert items[-1]["id"] == a.json()["id"] and items[-1]["purchased"] is True
    assert client.delete(f"/api/shopping/{a.json()['id']}", headers=admin).status_code == 200
    assert len(client.get("/api/shopping", headers=admin).json()) == 2

    # Buying never marks maintenance done and never creates an expense.
    assert client.get(f"/api/reminders/{rem['id']}", headers=admin).json()["due"]["status"] == "overdue"
    assert client.get(f"/api/entries?vehicle_id={v['id']}", headers=admin).json() == []


def test_validation(client, admin):
    assert client.post("/api/shopping", headers=admin, json={"description": "  "}).status_code == 400
    assert client.post("/api/shopping", headers=admin, json={"quantity": 2}).status_code == 400
    assert client.post("/api/shopping", headers=admin, json={"description": "x", "quantity": 0}).status_code == 422
    assert client.post("/api/shopping", headers=admin, json={"description": "x", "quantity": 1000}).status_code == 422
    assert client.post("/api/shopping", headers=admin, json={"description": "x" * 201}).status_code == 422
    assert client.post("/api/shopping", headers=admin, json={"description": "x", "type_key": "nope"}).status_code == 422


def test_private_per_user_and_links_are_checked(client, admin):
    bob = make_user(client, admin, "bob")
    v = make_vehicle(client, admin)
    rem = _reminder(client, admin, v)
    item = client.post("/api/shopping", headers=admin, json={"vehicle_id": v["id"], "description": "Oil"}).json()
    assert client.get("/api/shopping", headers=bob).json() == []
    assert client.get(f"/api/shopping/{item['id']}", headers=bob).status_code == 404
    assert client.delete(f"/api/shopping/{item['id']}", headers=bob).status_code == 404
    # Bob can't put an item on admin's vehicle; a link to admin's reminder is dropped.
    assert client.post("/api/shopping", headers=bob, json={"vehicle_id": v["id"], "description": "x"}).status_code == 400
    mine = client.post("/api/shopping", headers=bob, json={"reminder_id": rem["id"], "description": "x"}).json()
    assert mine["reminder_id"] is None


def test_syncs_and_follows_vehicle_deletion(client, admin):
    v = make_vehicle(client, admin)
    item_id = "11111111-2222-4333-8444-555555555555"
    r = client.post("/api/sync", headers=admin, json={"shopping": [{
        "id": item_id, "vehicle_id": v["id"], "description": "Wiper blades", "spec": "26 in + 18 in", "quantity": 2,
        "updated_at": "2026-01-01T00:00:00Z"}]}).json()
    assert r["results"]["shopping"][0]["status"] == "applied"
    pulled = client.get("/api/sync", headers=admin).json()["shopping"]
    assert [i["id"] for i in pulled] == [item_id] and pulled[0]["quantity"] == 2
    # Deleting the vehicle removes its shopping items too (and the phone learns about it).
    client.delete(f"/api/vehicles/{v['id']}", headers=admin)
    pulled = client.get("/api/sync", headers=admin).json()["shopping"]
    assert pulled[0]["deleted_at"] is not None
