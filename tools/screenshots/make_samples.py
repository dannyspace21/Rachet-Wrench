#!/usr/bin/env python3
"""
make_samples.py — creates the SAMPLE files used for README screenshots:
  * sample-sticker.png  a synthetic door-jamb tire placard (not a real car's)
  * sample-carfax.pdf   a synthetic "vehicle history" PDF in Carfax's layout
                        (made-up records; not a real Carfax report)
Run with the server's Python environment (needs Pillow and fpdf2).
"""
from pathlib import Path

from fpdf import FPDF
from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).resolve().parent


def sticker() -> None:
    img = Image.new("RGB", (1600, 900), (250, 250, 247))
    d = ImageDraw.Draw(img)
    big = ImageFont.load_default(size=46)
    mid = ImageFont.load_default(size=40)
    d.rectangle([20, 20, 1580, 880], outline=(30, 30, 30), width=6)
    d.rectangle([20, 20, 1580, 110], fill=(30, 30, 30))
    d.text((50, 40), "TIRE AND LOADING INFORMATION", fill="white", font=big)
    lines = ["SAMPLE STICKER - FOR SCREENSHOTS", "TIRE   SIZE              COLD TIRE PRESSURE",
             "FRONT  235/40R19 96W     240 KPA, 35 PSI", "REAR   265/35R19 98W     240 KPA, 35 PSI",
             "GVWR 4960 LB", "VIN 1HGCM82633A004352", "C/TR: 223/LA20", "MFD 04/15"]
    for i, line in enumerate(lines):
        d.text((60, 140 + i * 90), line, fill=(20, 20, 20), font=mid)
    img.save(HERE / "sample-sticker.png")


def carfax() -> None:
    pdf = FPDF()
    pdf.add_page()
    pdf.set_font("Helvetica", "B", 16)
    pdf.cell(0, 10, "SAMPLE Vehicle History Report (synthetic data)", new_x="LMARGIN", new_y="NEXT")
    pdf.set_font("Helvetica", size=10)
    rows = [
        "Date Mileage Source Comments",
        "06/14/2019 8,210 Lexus of Example City", "- Vehicle serviced", "- Oil and filter changed", "- Tires rotated",
        "01/09/2020 15,480 Example Tire & Auto", "- Four tires replaced", "- Alignment performed",
        "05/22/2021 22,950 Example State DMV", "- Title issued or updated",
        "11/03/2022 31,700 Lexus of Example City", "- Brake fluid flushed/changed", "- Cabin air filter replaced",
        "08/19/2023 39,020 Quick Lube Example", "- Oil and filter changed",
    ]
    for line in rows:
        pdf.cell(0, 7, line, new_x="LMARGIN", new_y="NEXT")
    pdf.output(str(HERE / "sample-carfax.pdf"))


if __name__ == "__main__":
    sticker()
    carfax()
    print("samples written")
