"""GVWR / GAWR from sticker text — shared cases with Android (catalog/sticker_weight_tests.json)."""

import json
from pathlib import Path

import pytest

from app.sticker_parser import parse_sticker_text, parse_weights

CASES = json.loads((Path(__file__).resolve().parents[2] / "catalog" / "sticker_weight_tests.json").read_text())["cases"]


@pytest.mark.parametrize("case", CASES, ids=[c["name"] for c in CASES])
def test_weights(case):
    assert parse_weights(case["text"]) == case["expect"]


def test_full_parse_uses_the_same_rules():
    got = parse_sticker_text("GVWR / PNBV   GAWR FR / PNBE AV   GAWR RR / PNBE AR\n2155 kg   1135 kg   1055 kg\n4750 lb   2500 lb   2330 lb")
    assert (got["gvwr"], got["gawr_front"], got["gawr_rear"]) == ("4750", "2500", "2330")
