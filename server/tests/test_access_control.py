"""
Access-control tests: one user must never be able to see, change, delete
or link to another user's vehicles, entries, files or sync data.
"""

import io

from conftest import make_user, make_vehicle
from PIL import Image


def _jpeg() -> bytes:
    buf = io.BytesIO()
    Image.new("RGB", (40, 30), (200, 50, 50)).save(buf, "JPEG")
    return buf.getvalue()


def test_users_cannot_touch_each_others_records(client, admin):
    bob = make_user(client, admin, "bob")
    v = make_vehicle(client, admin)
    e = client.post("/api/entries", headers=admin, json={"vehicle_id": v["id"], "date": "2025-01-02", "type_key": "oil_change"}).json()
    att = client.post("/api/attachments", headers=admin, data={"vehicle_id": v["id"], "entry_id": e["id"]},
                      files={"file": ("a.jpg", _jpeg(), "image/jpeg")}).json()

    # Bob sees nothing in his lists.
    assert client.get("/api/vehicles", headers=bob).json() == []
    assert client.get("/api/entries", headers=bob).json() == []
    # Direct access to admin's records looks like "not found".
    for path in (f"/api/vehicles/{v['id']}", f"/api/entries/{e['id']}", f"/api/attachments/{att['id']}",
                 f"/api/attachments/{att['id']}/file", f"/api/attachments/{att['id']}/thumb",
                 f"/api/vehicles/{v['id']}/report.pdf", f"/api/vehicles/{v['id']}/links"):
        assert client.get(path, headers=bob).status_code == 404, path
    assert client.patch(f"/api/vehicles/{v['id']}", headers=bob, json={"nickname": "mine"}).status_code == 404
    assert client.delete(f"/api/entries/{e['id']}", headers=bob).status_code == 404
    # Bob can't attach records or files to admin's vehicle.
    r = client.post("/api/entries", headers=bob, json={"vehicle_id": v["id"], "date": "2025-01-02", "type_key": "oil_change"})
    assert r.status_code == 400
    r = client.post("/api/attachments", headers=bob, data={"vehicle_id": v["id"]}, files={"file": ("a.jpg", _jpeg(), "image/jpeg")})
    assert r.status_code == 404
    # Listing by someone else's vehicle id is refused.
    assert client.get(f"/api/entries?vehicle_id={v['id']}", headers=bob).status_code == 404
    # Admin's data is untouched.
    assert client.get(f"/api/vehicles/{v['id']}", headers=admin).json()["nickname"] == ""


def test_sync_is_isolated_per_user(client, admin):
    bob = make_user(client, admin, "bob")
    v = make_vehicle(client, admin)
    pulled = client.get("/api/sync", headers=bob).json()
    assert pulled["vehicles"] == []
    # Bob tries to overwrite admin's vehicle through sync using its id.
    r = client.post("/api/sync", headers=bob, json={"vehicles": [{"id": v["id"], "make": "Hacked", "updated_at": "2099-01-01T00:00:00Z"}]})
    assert r.json()["results"]["vehicles"][0]["status"] == "rejected"
    assert client.get(f"/api/vehicles/{v['id']}", headers=admin).json()["make"] == "Lexus"


def test_cannot_move_record_to_another_vehicle(client, admin):
    v1 = make_vehicle(client, admin)
    v2 = make_vehicle(client, admin, model="IS 350")
    e = client.post("/api/entries", headers=admin, json={"vehicle_id": v1["id"], "date": "2025-01-02"}).json()
    r = client.patch(f"/api/entries/{e['id']}", headers=admin, json={"vehicle_id": v2["id"]})
    assert r.status_code == 400


def test_invalid_ids_rejected(client, admin):
    assert client.get("/api/entries?vehicle_id=../../etc", headers=admin).status_code == 422
    assert client.get("/api/vehicles/not-a-uuid", headers=admin).status_code == 404
