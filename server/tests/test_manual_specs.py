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


def test_catalog_lists_the_same_specs_as_the_manual_reader():
    import json
    from pathlib import Path
    from app.manual_specs import RULES
    cat = json.loads((Path(__file__).resolve().parents[2] / "catalog" / "catalog.json").read_text())
    assert sorted(cat["manual_specs"]) == sorted(RULES)
    assert set(cat["manual_specs"]) <= {s["key"] for s in cat["specs"]}


def test_weights_from_the_manual():
    pages = ["Vehicle specifications\nGross vehicle weight rating (GVWR) 5,000 lb (2,268 kg)\n"
             "Gross axle weight rating, front 2,535 lb (1,150 kg)\nGross axle weight rating, rear 2,205 lb (1,000 kg)"]
    got = {s["key"]: s["options"][0]["value"] for s in find_specs(pages)}
    assert (got["gvwr"], got["gawr_front"], got["gawr_rear"]) == ("5000", "2535", "2205")


TOYOTA_TABLE = """Specifications
Tires and wheels
Tire size 235/40R19 96W
Tire inflation pressure (Recommended cold tire inflation pressure)
Front 240 kPa (2.4 kgf/cm2 or bar, 35 psi)
Rear 240 kPa (2.4 kgf/cm2 or bar, 35 psi)
Spare tire T155/70D18 420 kPa (60 psi)
Wheel size 19 x 8J
Lug nut torque 103 N·m (76 ft·lbf)
Battery
Type 55D23L
Wiper blades
Driver side 650 mm (26 in.)
Passenger side 400 mm (16 in.)
Seating capacity 4 persons
Fuel: Unleaded gasoline, Octane Rating 91 [Research Octane Number 96] or higher
Curb weight 3,748 lb (1,700 kg)
Wheelbase 2,730 mm (107.5 in.)
Power steering fluid: Not applicable (electric)
Oil filter part number 04152-YZZA1"""

KIA_SENTENCES = """Lubricants and capacities
Windshield wiper blades: driver's 24 in. (600 mm), passenger's 16 in. (400 mm)
Tire size: front 225/40R18 92Y, rear 255/35R18 94Y
Tire pressure (cold): Front and rear 36 psi (250 kPa)
Battery: Group 47 (H5) AGM"""

MOTORCYCLE = """Specifications
Drive chain: DID 520VX3, 114 links
Front sprocket 15T  Rear sprocket 45T
Chain slack 25 - 35 mm (1.0 - 1.4 in)
Fork oil: Kawasaki KHL15-10 (10W)
Tire size front 120/70ZR17 M/C (58W) rear 180/55ZR17 M/C (73W)"""


def _first(pages):
    return {s["key"]: s["options"][0]["value"] for s in find_specs(pages)}


def _all(pages):
    return {s["key"]: [o["value"] for o in s["options"]] for s in find_specs(pages)}


def test_tires_wipers_battery_and_more_from_a_spec_table():
    got = _first([TOYOTA_TABLE])
    assert got["tire_size_front"] == got["tire_size_rear"] == "235/40R19 96W"   # one size = all four
    assert got["tire_size_spare"] == "T155/70D18"
    assert got["tire_pressure_front"] == got["tire_pressure_rear"] == "35 psi"
    assert got["rim_size_front"] == "19 x 8J"
    assert got["wiper_size_driver"] == "26 in" and got["wiper_size_passenger"] == "16 in"
    assert got["battery_group"] == "55D23L"
    assert got["lug_nut_torque"] == "76 ft-lb"           # ft-lb preferred over N·m
    assert got["seating_capacity"] == "4" and got["fuel_type"] == "91 octane"
    assert got["curb_weight"] == "3748 lb" and got["wheelbase"] == "107.5 in"
    assert got["oil_filter_part"] == "04152-YZZA1"
    assert "power_steering_fluid_type" not in got        # "Not applicable" is not a fluid


def test_front_and_rear_and_driver_and_passenger_are_kept_apart():
    got = _first([KIA_SENTENCES])
    assert got["tire_size_front"] == "225/40R18 92Y" and got["tire_size_rear"] == "255/35R18 94Y"
    assert got["tire_pressure_front"] == got["tire_pressure_rear"] == "36 psi"
    assert got["wiper_size_driver"] == "24 in" and got["wiper_size_passenger"] == "16 in"
    assert got["battery_group"] == "Group 47"
    assert "tire_size_spare" not in got


def test_motorcycle_manual():
    got = _all([MOTORCYCLE])
    assert got["chain_size"] == ["520 x 114"]
    assert got["sprocket_front"] == ["15"] and got["sprocket_rear"] == ["45"]
    assert got["chain_slack"] == ["25 - 35 mm"]
    assert got["fork_oil_type"] == ["10W"]               # not the tire's "58W" on the next line
    assert got["tire_size_rear"] == ["180/55ZR17 73W"]


CAR_EXTRAS = """Specifications
Light bulbs
Headlights Low beam H11 55W  High beam 9005 60W
Front fog lights H16 19W
Front turn signal lights 7444NA 28/8W
Rear turn signal lights 7440A 21W
Stop/tail lights 7443 21/5W
Back-up lights 921 16W
License plate lights 168 5W
Interior light LED
Air conditioning system refrigerant: HFO-1234yf (R-1234yf), 450 ± 25 g (15.9 ± 0.9 oz)
Spark plug: DENSO FK20HBR11, gap 1.0 - 1.1 mm
Battery: Group 24F, 650 CCA
Washer fluid capacity 4.5 L (4.8 qt)
Manual transmission oil: 75W-90 GL-4, capacity 2.2 L (2.3 qt)
Automatic transmission fluid ATF WS
Front differential oil 75W-85 GL-5
Rear differential oil 75W-90 GL-5
Transfer case oil 75W GL-5
Maximum trailer weight 3,500 lb (1,588 kg)
Vehicle capacity weight 850 lbs (385 kg)"""

BIKE_TORQUES = """Specifications
Valve clearance (cold) IN 0.10 - 0.20 mm EX 0.20 - 0.30 mm
Idle speed 1,300 ± 100 r/min
Torque
Front axle 108 N·m (80 ft·lbf)
Rear axle nut 127 N·m (94 ft·lbf)
Engine oil drain bolt 30 N·m (22 ft·lbf)
Brake pad lining wear limit 1 mm"""


def test_bulbs_refrigerant_and_drivetrain_oils():
    got = _all([CAR_EXTRAS])
    bulbs = {k: got[k][0] for k in got if k.startswith("bulb_")}
    assert bulbs == {"bulb_low_beam": "H11", "bulb_high_beam": "9005", "bulb_fog": "H16", "bulb_turn_front": "7444NA",
                     "bulb_turn_rear": "7440A", "bulb_brake_tail": "7443", "bulb_reverse": "921", "bulb_license": "168"}
    assert got["ac_refrigerant"] == ["R-1234yf, 450 g"]
    assert got["spark_plug_gap"] == ["1.0–1.1 mm"] and got["battery_cca"] == ["650 CCA"]
    assert got["washer_fluid_capacity"] == ["4.5 L (4.8 qt)"]
    assert got["manual_transmission_oil_type"] == ["75W-90 GL-4"] and got["manual_transmission_oil_capacity"] == ["2.2 L (2.3 qt)"]
    assert got["transmission_fluid_type"] == ["ATF WS"]                     # the manual gearbox isn't mixed in
    assert got["front_differential_fluid_type"] == ["75W-85 GL-5"]
    assert got["differential_fluid_type"] == ["75W-90 GL-5"]               # rear, not the front one
    assert got["transfer_case_fluid_type"] == ["75W GL-5"]
    assert got["towing_capacity"] == ["3500 lb"] and got["payload_capacity"] == ["850 lb"]


def test_motorcycle_torques_valves_and_idle():
    got = _first([BIKE_TORQUES])
    assert (got["front_axle_torque"], got["rear_axle_torque"], got["drain_bolt_torque"]) == ("80 ft-lb", "94 ft-lb", "22 ft-lb")
    assert got["valve_clearance_intake"] == "0.10–0.20 mm" and got["valve_clearance_exhaust"] == "0.20–0.30 mm"
    assert got["idle_speed"] == "1300 ± 100 rpm" and got["brake_pad_wear_limit"] == "1 mm"
