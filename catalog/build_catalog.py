#!/usr/bin/env python3
"""
build_catalog.py — writes catalog.json, the single shared list of:

  * every maintenance / repair TYPE (oil change, brake pads, chain lube ...),
    each with a matching icon file name in /icons/types/,
  * every vehicle SPEC field (oil viscosity, tire sizes, paint code ...),
  * the official manufacturer websites (parts store + owner portal) per make.

Why a generator instead of hand-editing JSON?  Python lets us keep comments
next to each entry, and the script guarantees the JSON is always valid.
The server, the web app and the Android app all read the generated
catalog.json, so the three apps can never disagree about type names.

Run:  python3 catalog/build_catalog.py
"""

import json
from pathlib import Path

HERE = Path(__file__).resolve().parent

# ---------------------------------------------------------------------------
# Maintenance & repair types.
# Each tuple is: (key, human label, category, applies_to)
#   key        -> stable ID stored in the database and the icon file name
#   category   -> used to group types in pick lists
#   applies_to -> "car", "motorcycle" or "both" (filters the pick list)
# ---------------------------------------------------------------------------
TYPES = [
    # --- Engine oil & filters ---------------------------------------------
    ("oil_change", "Oil change", "Engine", "both"),
    ("oil_filter", "Oil filter", "Engine", "both"),
    ("air_filter", "Engine air filter", "Engine", "both"),
    ("cabin_filter", "Cabin air filter", "Interior", "car"),
    ("fuel_filter", "Fuel filter", "Fuel system", "both"),
    ("spark_plugs", "Spark plugs", "Ignition", "both"),
    ("ignition_coils", "Ignition coils", "Ignition", "both"),
    ("serpentine_belt", "Serpentine / drive belt", "Engine", "car"),
    ("timing_belt", "Timing belt", "Engine", "both"),
    ("timing_chain", "Timing chain", "Engine", "both"),
    ("valve_adjustment", "Valve adjustment / desmo service", "Engine", "both"),
    ("pcv_valve", "PCV valve", "Engine", "car"),
    ("throttle_body", "Throttle body cleaning / sync", "Fuel system", "both"),
    ("fuel_system_cleaning", "Fuel system / injector cleaning", "Fuel system", "both"),
    ("engine_mounts", "Engine / transmission mounts", "Engine", "car"),
    # --- Cooling ----------------------------------------------------------
    ("coolant", "Coolant flush / top-up", "Cooling", "both"),
    ("radiator", "Radiator", "Cooling", "both"),
    ("water_pump", "Water pump", "Cooling", "both"),
    ("thermostat", "Thermostat", "Cooling", "both"),
    ("hoses", "Hoses", "Cooling", "both"),
    # --- Fluids -----------------------------------------------------------
    ("transmission_fluid", "Transmission fluid", "Fluids", "both"),
    ("brake_fluid", "Brake fluid", "Brakes", "both"),
    ("clutch_fluid", "Clutch fluid", "Fluids", "both"),
    ("power_steering_fluid", "Power steering fluid", "Fluids", "car"),
    ("differential_fluid", "Differential fluid", "Fluids", "car"),
    ("transfer_case_fluid", "Transfer case fluid", "Fluids", "car"),
    ("final_drive_oil", "Final drive oil", "Fluids", "motorcycle"),
    ("washer_fluid", "Washer fluid", "Fluids", "car"),
    # --- Tires & wheels -----------------------------------------------------
    ("tire_rotation", "Tire rotation", "Tires & wheels", "car"),
    ("tire_tread_depth", "Tire tread depth", "Tires & wheels", "both"),
    ("tire_pressure", "Tire pressure", "Tires & wheels", "both"),
    ("tire_replacement", "Tire replacement", "Tires & wheels", "both"),
    ("tire_repair", "Flat / puncture repair", "Tires & wheels", "both"),
    ("wheel_alignment", "Wheel alignment", "Tires & wheels", "car"),
    ("wheel_balance", "Wheel balancing", "Tires & wheels", "both"),
    ("tpms_sensor", "TPMS sensor", "Tires & wheels", "both"),
    ("wheel_bearings", "Wheel bearings", "Tires & wheels", "both"),
    ("wheel_repair", "Wheel repair / refinish", "Tires & wheels", "both"),
    # --- Brakes -----------------------------------------------------------
    ("brake_pads", "Brake pads", "Brakes", "both"),
    ("brake_rotors", "Brake rotors / discs", "Brakes", "both"),
    ("brake_calipers", "Brake calipers", "Brakes", "both"),
    ("brake_lines", "Brake lines / hoses", "Brakes", "both"),
    # --- Electrical -------------------------------------------------------
    ("battery", "Battery", "Electrical", "both"),
    ("alternator", "Alternator / charging", "Electrical", "both"),
    ("starter", "Starter motor", "Electrical", "both"),
    ("lights_bulbs", "Lights / bulbs", "Electrical", "both"),
    ("fuses", "Fuses / relays", "Electrical", "both"),
    ("diagnostics", "Diagnostics / check engine light", "Electrical", "both"),
    # --- Suspension & steering -------------------------------------------
    ("suspension", "Suspension", "Suspension & steering", "both"),
    ("shocks_struts", "Shocks / struts", "Suspension & steering", "car"),
    ("steering", "Steering / tie rods", "Suspension & steering", "car"),
    ("cv_axles", "CV axles / boots", "Drivetrain", "car"),
    # --- Drivetrain & exhaust ----------------------------------------------
    ("clutch", "Clutch", "Drivetrain", "both"),
    ("exhaust", "Exhaust / muffler", "Exhaust", "both"),
    ("catalytic_converter", "Catalytic converter", "Exhaust", "both"),
    # --- Climate ----------------------------------------------------------
    ("ac_service", "A/C service / recharge", "Climate", "car"),
    # --- Body & interior --------------------------------------------------
    ("wipers", "Wiper blades", "Body & glass", "car"),
    ("glass", "Windshield / glass", "Body & glass", "car"),
    ("body_paint", "Body / paint repair", "Body & glass", "both"),
    ("detailing", "Detailing / wash", "Body & glass", "both"),
    ("locks_keys", "Locks / keys / fob battery", "Body & glass", "both"),
    # --- Motorcycle specific ----------------------------------------------
    ("chain_adjustment", "Chain adjustment", "Motorcycle", "motorcycle"),
    ("chain_lube", "Chain clean & lube", "Motorcycle", "motorcycle"),
    ("chain_replacement", "Chain replacement", "Motorcycle", "motorcycle"),
    ("sprockets", "Sprockets", "Motorcycle", "motorcycle"),
    ("shock_maintenance", "Rear shock service", "Motorcycle", "motorcycle"),
    ("fork_oil", "Fork oil", "Motorcycle", "motorcycle"),
    ("fork_seals", "Fork seals", "Motorcycle", "motorcycle"),
    ("steering_head_bearings", "Steering head bearings", "Motorcycle", "motorcycle"),
    ("cables", "Clutch / throttle cables", "Motorcycle", "motorcycle"),
    ("brake_lever", "Levers / controls", "Motorcycle", "motorcycle"),
    # --- Paperwork / other ------------------------------------------------
    ("scheduled_service", "Scheduled service (e.g. 30,000-mile)", "Paperwork", "both"),
    ("inspection", "Inspection", "Paperwork", "both"),
    ("emissions", "Emissions test", "Paperwork", "both"),
    ("registration", "Registration", "Paperwork", "both"),
    ("insurance", "Insurance", "Paperwork", "both"),
    ("recall", "Recall / service campaign", "Paperwork", "both"),
    ("accident_damage", "Accident / damage repair", "Repairs", "both"),
    ("general_repair", "General repair", "Repairs", "both"),
    ("custom", "Custom / other", "Other", "both"),
]

# ---------------------------------------------------------------------------
# Vehicle spec fields.  (key, label, group, unit hint)
# These are the ONLY fields the app stores as "specs".  Values are never
# guessed: they come from the VIN decoder, the door sticker, or the user.
# ---------------------------------------------------------------------------
SPECS = [
    # Engine & oil
    ("engine", "Engine", "Engine & oil", ""),
    ("displacement_l", "Displacement", "Engine & oil", "L"),
    ("cylinders", "Cylinders", "Engine & oil", ""),
    ("fuel_type", "Fuel type", "Engine & oil", ""),
    ("oil_type", "Engine oil type / viscosity", "Engine & oil", "e.g. 0W-20 full synthetic"),
    ("oil_capacity", "Engine oil capacity (with filter)", "Engine & oil", "qt or L"),
    ("oil_filter_part", "Oil filter part number", "Engine & oil", ""),
    # Fluids & capacities
    ("coolant_type", "Coolant type", "Fluids & capacities", ""),
    ("coolant_capacity", "Coolant capacity", "Fluids & capacities", "qt or L"),
    ("transmission_fluid_type", "Transmission fluid type", "Fluids & capacities", ""),
    ("transmission_fluid_capacity", "Transmission fluid capacity", "Fluids & capacities", "qt or L"),
    ("brake_fluid_type", "Brake fluid type", "Fluids & capacities", "e.g. DOT 4"),
    ("differential_fluid_type", "Differential fluid type", "Fluids & capacities", ""),
    ("power_steering_fluid_type", "Power steering fluid type", "Fluids & capacities", ""),
    ("fork_oil_type", "Fork oil type", "Fluids & capacities", ""),
    ("fuel_capacity", "Fuel tank capacity", "Fluids & capacities", "gal or L"),
    # Tires & wheels
    ("tire_size_front", "Tire size — front", "Tires & wheels", "e.g. 235/40R19 96W"),
    ("tire_size_rear", "Tire size — rear", "Tires & wheels", "e.g. 265/35R19 98W"),
    ("tire_size_spare", "Tire size — spare", "Tires & wheels", ""),
    ("tire_pressure_front", "Tire pressure — front (cold)", "Tires & wheels", "psi"),
    ("tire_pressure_rear", "Tire pressure — rear (cold)", "Tires & wheels", "psi"),
    ("tire_pressure_spare", "Tire pressure — spare (cold)", "Tires & wheels", "psi"),
    ("rim_size_front", "Rim size — front", "Tires & wheels", "in"),
    ("rim_size_rear", "Rim size — rear", "Tires & wheels", "in"),
    ("lug_nut_torque", "Lug nut torque", "Tires & wheels", "ft-lb"),
    # Body & build
    ("body_style", "Body style", "Body & build", ""),
    ("drive_type", "Drive type", "Body & build", "e.g. RWD"),
    ("transmission", "Transmission", "Body & build", ""),
    ("trim", "Trim", "Body & build", ""),
    ("trim_code", "Trim / option code", "Body & build", ""),
    ("paint_code", "Paint code", "Body & build", ""),
    ("paint_color", "Paint color name", "Body & build", ""),
    ("build_date", "Build date", "Body & build", "MM/YY"),
    ("plant", "Assembly plant", "Body & build", ""),
    ("gvwr", "GVWR (gross vehicle weight rating)", "Body & build", "lb"),
    ("gawr_front", "GAWR — front axle", "Body & build", "lb"),
    ("gawr_rear", "GAWR — rear axle", "Body & build", "lb"),
    ("seating_capacity", "Seating capacity", "Body & build", ""),
    ("battery_group", "Battery group size", "Electrical", ""),
    ("wiper_size_driver", "Wiper size — driver", "Electrical", "in"),
    ("wiper_size_passenger", "Wiper size — passenger", "Electrical", "in"),
    ("spark_plug_part", "Spark plug part number", "Electrical", ""),
    ("chain_size", "Drive chain size", "Motorcycle", "e.g. 520 x 108"),
    ("sprocket_front", "Front sprocket teeth", "Motorcycle", ""),
    ("sprocket_rear", "Rear sprocket teeth", "Motorcycle", ""),
    ("chain_slack", "Chain slack", "Motorcycle", "mm"),
]

# Labels shown next to every spec value so you always know where it came from.
SPEC_SOURCES = {
    "vin": "From VIN",
    "sticker": "From door sticker",
    "user": "Entered by me",
    "unverified": "Unverified",
    "provider": "From VIN spec service",
    "carfax": "From Carfax",
}

# ---------------------------------------------------------------------------
# Official manufacturer websites.  ONLY links — the app never scrapes them.
#  parts  = the manufacturer's official online parts catalog
#  owners = the manufacturer's owner portal (manuals, recalls, maintenance)
# A parts value of None means we could not confirm a manufacturer-run parts
# store, so the app shows the owner portal instead (never a dealer site).
# If a make is missing, the app falls back to a plain web search link the
# user opens themselves.  You can override both links per vehicle.
# ---------------------------------------------------------------------------
MAKES = {
    "Acura": {"parts": None, "owners": "https://owners.acura.com/"},
    "Audi": {"parts": "https://parts.audiusa.com/", "owners": "https://www.audiusa.com/us/web/en/myaudi.html"},
    "BMW": {"parts": "https://www.bmwusa.com/parts-and-accessories.html", "owners": "https://www.bmwusa.com/owners.html"},
    "Chevrolet": {"parts": "https://www.gmparts.com/", "owners": "https://www.chevrolet.com/support"},
    "Ducati": {"parts": "https://www.ducati.com/us/en/service-maintenance/spare-parts", "owners": "https://www.ducati.com/us/en/service-maintenance/owner-manuals"},
    "Ford": {"parts": "https://parts.ford.com/", "owners": "https://www.ford.com/support/"},
    "GMC": {"parts": "https://www.gmparts.com/", "owners": "https://www.gmc.com/support"},
    "Harley-Davidson": {"parts": "https://www.harley-davidson.com/us/en/shop/c/motorcycle-parts", "owners": "https://www.harley-davidson.com/us/en/owners/index.html"},
    "Honda": {"parts": None, "owners": "https://owners.honda.com/"},
    "Hyundai": {"parts": None, "owners": "https://owners.hyundaiusa.com/"},
    "Infiniti": {"parts": "https://parts.infinitiusa.com/", "owners": "https://owners.infinitiusa.com/"},
    "Kawasaki": {"parts": "https://www.kawasaki.com/en-us/parts", "owners": "https://www.kawasaki.com/en-us/owner-center"},
    "Kia": {"parts": None, "owners": "https://owners.kia.com/"},
    "Lexus": {"parts": "https://parts.lexus.com/", "owners": "https://www.lexus.com/owners"},
    "Mazda": {"parts": "https://www.mazdausa.com/owners/parts-and-accessories", "owners": "https://www.mazdausa.com/owners"},
    "Mercedes-Benz": {"parts": "https://www.mbusa.com/en/owners/parts-and-accessories", "owners": "https://www.mbusa.com/en/owners"},
    "Nissan": {"parts": "https://parts.nissanusa.com/", "owners": "https://owners.nissanusa.com/"},
    "Porsche": {"parts": "https://www.porsche.com/usa/accessoriesandservice/", "owners": "https://www.porsche.com/usa/accessoriesandservice/"},
    "Subaru": {"parts": "https://parts.subaru.com/", "owners": "https://www.subaru.com/owners.html"},
    "Suzuki": {"parts": "https://suzukicycles.com/parts", "owners": "https://suzukicycles.com/owners"},
    "Tesla": {"parts": "https://www.tesla.com/shop", "owners": "https://www.tesla.com/support"},
    "Toyota": {"parts": "https://parts.toyota.com/", "owners": "https://www.toyota.com/owners/"},
    "Triumph": {"parts": "https://www.triumphmotorcycles.com/accessories", "owners": "https://www.triumphmotorcycles.com/owners"},
    "Volkswagen": {"parts": "https://parts.vw.com/", "owners": "https://www.vw.com/en/owners-and-services.html"},
    "Yamaha": {"parts": "https://www.yamahamotorsports.com/parts", "owners": "https://www.yamahamotorsports.com/owner-center"},
}


def main() -> None:
    """Assemble the three lists into one JSON document and write it to disk."""
    keys = [t[0] for t in TYPES]
    # Guard against copy/paste mistakes: every key must be unique.
    assert len(keys) == len(set(keys)), "duplicate maintenance type key"
    spec_keys = [s[0] for s in SPECS]
    assert len(spec_keys) == len(set(spec_keys)), "duplicate spec key"

    catalog = {
        "version": 1,
        "types": [
            {"key": k, "label": label, "category": cat, "applies_to": applies, "icon": f"{k}.svg"}
            for (k, label, cat, applies) in TYPES
        ],
        "specs": [
            {"key": k, "label": label, "group": group, "hint": hint}
            for (k, label, group, hint) in SPECS
        ],
        "spec_sources": SPEC_SOURCES,
        "makes": MAKES,
    }
    out = HERE / "catalog.json"
    out.write_text(json.dumps(catalog, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    # The Android app bundles the same catalog (used offline).
    android_assets = HERE.parent / "android" / "app" / "src" / "main" / "assets"
    if android_assets.is_dir():
        (android_assets / "catalog.json").write_text(out.read_text(encoding="utf-8"), encoding="utf-8")
    print(f"wrote {out} ({len(TYPES)} types, {len(SPECS)} spec fields, {len(MAKES)} makes)")


if __name__ == "__main__":
    main()
