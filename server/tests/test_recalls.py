"""Recalls: looked up by year/make/model, kept per vehicle with the owner's status."""

from conftest import make_user, make_vehicle

from app.routers import vehicles as vroutes
from app.spec_parts import recall_id

FAKE = [
    {"campaign": "20V012000", "component": "FUEL SYSTEM", "summary": "Fuel pump may fail.", "consequence": "Stall.",
     "remedy": "Dealers replace the pump.", "report_date": "2020-01-13", "park_it": False},
    {"campaign": "15V001000", "component": "AIR BAGS", "summary": "Inflator.", "consequence": "Injury.",
     "remedy": "Replace.", "report_date": "2015-02-01", "park_it": True},
]


def _fake(monkeypatch, rows):
    monkeypatch.setattr(vroutes, "nhtsa_recalls", lambda make, model, year: rows)


def test_refresh_adds_recalls_once_and_keeps_owner_status(client, admin, monkeypatch):
    _fake(monkeypatch, FAKE[:1])
    v = make_vehicle(client, admin, year=2015, make="Lexus", model="RC 350")
    assert client.post(f"/api/vehicles/{v['id']}/recalls/refresh", headers=admin).json() == {"added": 1, "total": 1}
    rec = client.get(f"/api/recalls?vehicle_id={v['id']}", headers=admin).json()
    assert len(rec) == 1 and rec[0]["status"] == "open" and rec[0]["id"] == recall_id(v["id"], "20V012000")
    # The owner marks it done; a later lookup (with a new recall) must not undo that.
    client.patch(f"/api/recalls/{rec[0]['id']}", headers=admin, json={"status": "done", "done_date": "2026-11-01"})
    _fake(monkeypatch, FAKE)
    assert client.post(f"/api/vehicles/{v['id']}/recalls/refresh", headers=admin).json() == {"added": 1, "total": 2}
    rec = {r["campaign"]: r for r in client.get(f"/api/recalls?vehicle_id={v['id']}", headers=admin).json()}
    assert rec["20V012000"]["status"] == "done" and rec["15V001000"]["status"] == "open" and rec["15V001000"]["park_it"]
    # Newest recall first.
    assert [r["campaign"] for r in client.get(f"/api/recalls?vehicle_id={v['id']}", headers=admin).json()] == ["20V012000", "15V001000"]


def test_phone_copy_of_a_lookup_never_undoes_the_owner(client, admin, monkeypatch):
    """The phone looks up the same recall offline (same id, 1970 edit time): the owner's status wins."""
    _fake(monkeypatch, FAKE[:1])
    v = make_vehicle(client, admin, year=2015, make="Lexus", model="RC 350")
    client.post(f"/api/vehicles/{v['id']}/recalls/refresh", headers=admin)
    rid = recall_id(v["id"], "20V012000")
    client.patch(f"/api/recalls/{rid}", headers=admin, json={"status": "not_applicable"})
    r = client.post("/api/sync", headers=admin, json={"recalls": [{"id": rid, "vehicle_id": v["id"], "campaign": "20V012000",
                                                                   "status": "open", "updated_at": "1970-01-01T00:00:00Z"}]}).json()
    assert r["results"]["recalls"][0]["status"] == "kept_server_copy"
    assert client.get(f"/api/recalls/{rid}", headers=admin).json()["status"] == "not_applicable"


def test_needs_year_make_model_and_is_private(client, admin, monkeypatch):
    _fake(monkeypatch, FAKE)
    v = make_vehicle(client, admin, make="Lexus")
    assert client.post(f"/api/vehicles/{v['id']}/recalls/refresh", headers=admin).status_code == 400
    v2 = make_vehicle(client, admin, year=2015, make="Lexus", model="RC 350")
    client.post(f"/api/vehicles/{v2['id']}/recalls/refresh", headers=admin)
    bob = make_user(client, admin, "bob")
    assert client.get("/api/recalls", headers=bob).json() == []
    assert client.post(f"/api/vehicles/{v2['id']}/recalls/refresh", headers=bob).status_code == 404
    assert client.patch(f"/api/recalls/{recall_id(v2['id'], '20V012000')}", headers=bob, json={"status": "done"}).status_code == 404


def test_parse_nhtsa_response(monkeypatch):
    """The NHTSA answer is turned into tidy rows (date format, campaign, park-it warning)."""
    import io
    import json

    from app import vin

    body = {"results": [{"NHTSACampaignNumber": "20V-012000", "Component": "FUEL", "Summary": "s", "Consequence": "c",
                         "Remedy": "r", "ReportReceivedDate": "13/01/2020", "parkIt": False, "parkOutSide": True}]}

    class Resp(io.BytesIO):
        def __enter__(self): return self
        def __exit__(self, *a): return False

    monkeypatch.setattr(vin.urllib.request, "urlopen", lambda req, timeout: Resp(json.dumps(body).encode()))
    vin._RECALL_CACHE.clear()
    rows = vin.nhtsa_recalls("Lexus", "RC 350", 2015)
    assert rows == [{"campaign": "20V012000", "component": "FUEL", "summary": "s", "consequence": "c", "remedy": "r",
                     "report_date": "2020-01-13", "park_it": True}]
