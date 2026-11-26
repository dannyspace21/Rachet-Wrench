"""Owner's manual -> proposed fluid specs (synthetic manual pages in common layouts)."""

from app.manual_specs import find_specs

TOYOTA_STYLE = [
    "Introduction\nRead this manual carefully.",
    "4-3. Driving tips\nUse engine oil that meets the recommendations. Change the oil regularly.",
    """9-1. Specifications
Maintenance data (fuel, oil level, etc.)
Engine
Model 2GR-FSE
Lubrication system
Oil capacity (Drain and refill — reference*)
With filter
5.7 L (6.0 qt., 5.0 Imp. qt.)
Engine oil selection
Toyota Genuine Motor Oil SAE 0W-20 API SN, ILSAC GF-5 multigrade engine oil is used in your vehicle.
Cooling system
Capacity
9.0 L (9.5 qt., 7.9 Imp. qt.)
Coolant type
Use either of the following: Toyota Super Long Life Coolant or similar high quality ethylene glycol-based coolant
Automatic transmission
Fluid capacity 8.4 L (8.9 qt.)
Fluid type Toyota Genuine ATF WS
Brakes
Fluid type SAE J1703 or FMVSS No.116 DOT 3
Fuel tank capacity 66 L (17.4 gal., 14.5 Imp. gal.)
Spark plugs
Make DENSO FK20HR-A8
Tires and wheels
Lug nut torque 76 ft·lbf (103 N·m)""",
    "In an emergency, if 0W-20 oil is not available, SAE 5W-20 engine oil may be used.",
]

KIA_STYLE = [
    """Recommended lubricants and capacities
Engine oil (drain and refill) with oil filter: 4.0 L (4.23 US qt.)
Recommended engine oil viscosity SAE 5W-20, API SN or above.
Engine coolant capacity: 6.5 L (6.87 US qt.)
Engine coolant: Mixture of antifreeze and water (ethylene glycol-based coolant for aluminum radiator)
Automatic transaxle fluid: SK ATF SP-IV, Hyundai Genuine ATF SP-IV
Brake/clutch fluid: FMVSS 116 DOT-3 or DOT-4
Fuel tank capacity 50 L (13.2 US gal.)""",
]


def _by_key(pages):
    return {s["key"]: s for s in find_specs(pages)}


def test_toyota_style_table():
    got = _by_key(TOYOTA_STYLE)
    assert got["oil_type"]["options"][0]["value"] == "0W-20 (API SN)"
    assert got["oil_type"]["options"][0]["page"] == 3
    # The emergency alternative is offered too, but ranked after the spec page's value.
    assert any(o["value"] == "5W-20" for o in got["oil_type"]["options"][1:])
    assert got["oil_capacity"]["options"][0]["value"] == "5.7 L (6.0 qt., 5.0 Imp. qt.)"
    assert got["coolant_capacity"]["options"][0]["value"].startswith("9.0 L")
    assert got["coolant_type"]["options"][0]["value"] == "Toyota Super Long Life Coolant"
    assert got["transmission_fluid_type"]["options"][0]["value"] == "ATF WS"
    assert got["transmission_fluid_capacity"]["options"][0]["value"].startswith("8.4 L")
    assert got["brake_fluid_type"]["options"][0]["value"] == "DOT 3"
    assert got["fuel_capacity"]["options"][0]["value"].startswith("66 L")
    assert got["spark_plug_part"]["options"][0]["value"] == "DENSO FK20HR-A8"
    assert got["lug_nut_torque"]["options"][0]["value"] == "76 ft-lb"
    # Every proposal says where it came from.
    for s in got.values():
        assert all(o["page"] >= 1 and o["snippet"] for o in s["options"])


def test_kia_style_sentences():
    got = _by_key(KIA_STYLE)
    assert got["oil_capacity"]["options"][0]["value"].startswith("4.0 L")
    assert got["oil_type"]["options"][0]["value"] == "5W-20 (API SN)"
    assert got["coolant_capacity"]["options"][0]["value"].startswith("6.5 L")
    assert got["transmission_fluid_type"]["options"][0]["value"] == "ATF SP-IV"
    assert got["brake_fluid_type"]["options"][0]["value"] == "DOT 3"
    assert got["fuel_capacity"]["options"][0]["value"].startswith("50 L")


def test_nothing_is_invented():
    assert find_specs(["Welcome to your new vehicle.", "Adjusting the mirrors.", ""]) == []
    # Words without a value next to them don't produce a proposal.
    assert "oil_type" not in _by_key(["Check the engine oil level regularly."])


def _manual_pdf(pages):
    from fpdf import FPDF

    pdf = FPDF()
    pdf.set_font("Helvetica", size=10)
    for text in pages:
        pdf.add_page()
        for line in text.splitlines():
            pdf.multi_cell(0, 5, line.replace("—", "-").replace("·", "."), new_x="LMARGIN", new_y="NEXT")
    return bytes(pdf.output())


def test_scan_and_apply_through_the_api(client, admin):
    from conftest import make_vehicle

    v = make_vehicle(client, admin, year=2015)
    client.post("/api/specs", headers=admin, json={"vehicle_id": v["id"], "key": "oil_type", "value": "5W-30", "source": "user"})
    r = client.post(f"/api/vehicles/{v['id']}/manual/scan", headers=admin,
                    files={"file": ("manual.pdf", _manual_pdf(TOYOTA_STYLE), "application/pdf")})
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["pages"] == 4 and body["attachment"]["category"] == "manual"
    found = {s["key"]: s for s in body["specs"]}
    assert found["oil_type"]["options"][0]["value"] == "0W-20 (API SN)" and found["oil_type"]["current"] == "5W-30"
    assert found["brake_fluid_type"]["options"][0]["value"] == "DOT 3"
    # Scanning the saved manual again works without uploading it.
    again = client.post(f"/api/vehicles/{v['id']}/manual/scan", headers=admin, data={"attachment_id": body["attachment"]["id"]})
    assert again.status_code == 200 and again.json()["specs"] == body["specs"]
    # Only approved values are saved, labelled "From owner's manual".
    saved = client.post(f"/api/vehicles/{v['id']}/manual/apply", headers=admin,
                        json={"fields": [{"key": "oil_type", "value": "0W-20 (API SN)"}, {"key": "brake_fluid_type", "value": "DOT 3"}]})
    assert saved.status_code == 200
    specs = {s["key"]: s for s in client.get(f"/api/specs?vehicle_id={v['id']}", headers=admin).json()}
    assert specs["oil_type"]["value"] == "0W-20 (API SN)" and specs["oil_type"]["source"] == "manual"
    assert specs["brake_fluid_type"]["source"] == "manual" and "coolant_type" not in specs


def test_scan_needs_a_pdf(client, admin):
    from conftest import make_vehicle

    v = make_vehicle(client, admin)
    assert client.post(f"/api/vehicles/{v['id']}/manual/scan", headers=admin).status_code == 400
    r = client.post(f"/api/vehicles/{v['id']}/manual/scan", headers=admin, files={"file": ("x.pdf", b"not a pdf at all", "application/pdf")})
    assert r.status_code == 415


def test_value_from_the_next_section_is_not_offered():
    pages = ["Specifications\nEngine oil\nOil capacity with filter 5.7 L (6.0 qt)\nCooling system\nCoolant capacity 9.1 L (9.6 qt)\n"
             "Coolant type Toyota Super Long Life Coolant\nAutomatic transmission\nFluid capacity 8.4 L (8.9 qt)"]
    got = {s["key"]: [o["value"] for o in s["options"]] for s in find_specs(pages)}
    assert got["oil_capacity"] == ["5.7 L (6.0 qt)"]
    assert got["coolant_capacity"] == ["9.1 L (9.6 qt)"]
    assert got["transmission_fluid_capacity"] == ["8.4 L (8.9 qt)"]
