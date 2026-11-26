"""catalog/affiliates.json: invalid or placeholder affiliate settings never reach the apps."""

import importlib.util
import json
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]


def _builder(tmp_path, affiliates):
    spec = importlib.util.spec_from_file_location("build_catalog_test", ROOT / "catalog" / "build_catalog.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    (tmp_path / "affiliates.json").write_text(json.dumps(affiliates))
    mod.HERE = tmp_path
    return mod


def amazon(**kw):
    a = {"program": "Amazon Associates", "enabled": True, "platforms": ["web"], "method": "param",
         "params": {"tag": "realtag-20"}, "wrap": "", "disclosure": "As an Amazon Associate I earn from qualifying purchases."}
    a.update(kw)
    return {"amazon": a}


def test_shipped_settings_are_all_off():
    data = json.loads((ROOT / "catalog" / "affiliates.json").read_text())
    for key, a in data.items():
        if not key.startswith("_"):
            assert a["enabled"] is False and a["platforms"] == [], key
            assert all(not v for v in a.get("params", {}).values()) and not a.get("wrap"), f"{key}: no identifiers shipped"
    catalog = json.loads((ROOT / "catalog" / "catalog.json").read_text())
    assert all(not r["affiliate"]["enabled"] for r in catalog["retailers"])


def test_valid_setting_accepted(tmp_path):
    out = _builder(tmp_path, amazon()).load_affiliates()
    assert out["amazon"]["params"] == {"tag": "realtag-20"} and out["amazon"]["platforms"] == ["web"]


@pytest.mark.parametrize("bad", [
    {"params": {"tag": "YOUR-TAG-20"}},                 # placeholder
    {"params": {"tag": ""}},                            # missing identifier
    {"platforms": []},                                  # enabled without saying where it's approved
    {"platforms": ["ios"]},                             # unknown platform
    {"disclosure": ""},                                 # no disclosure
    {"method": "wrap", "params": {}, "wrap": "http://net.example/c/1?u={url}"},          # not https
    {"method": "wrap", "params": {}, "wrap": "https://net.example/c/XXXX/1?u={url}"},    # placeholder
    {"method": "wrap", "params": {}, "wrap": "https://net.example/c/123/1"},             # no {url}
])
def test_invalid_settings_refused(tmp_path, bad):
    with pytest.raises(AssertionError):
        _builder(tmp_path, amazon(**bad)).load_affiliates()


def test_unknown_retailer_refused(tmp_path):
    with pytest.raises(AssertionError):
        _builder(tmp_path, {"ebay": amazon()["amazon"]}).load_affiliates()
