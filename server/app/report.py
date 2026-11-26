"""
report.py — builds the downloadable "Vehicle History Report" PDF.

Layout (top to bottom):
  1. Logo, title, vehicle name, date generated
  2. Vehicle summary box + cost totals
  3. Specs table, each value with its source label (From VIN, ...)
  4. Every maintenance and repair entry in date order, each with its type
     icon, a clear heading, date / mileage / cost / who did it, details, and
     all photos attached to that entry
  5. Page numbers in the footer

Uses fpdf2, a maintained pure-Python PDF library.  Only our own sanitized
images (already stripped of metadata on upload) are embedded.
"""

from __future__ import annotations

import io
import re
from datetime import date
from pathlib import Path

from fpdf import FPDF
from sqlalchemy import select
from sqlalchemy.orm import Session

from .catalog import load_catalog, spec_label, type_label
from .config import asset_path
from .models import Attachment, Entry, User, Vehicle, VehicleSpec
from .uploads import file_path

BRAND = (234, 88, 12)  # orange
INK = (17, 24, 39)
MUTED = (100, 116, 139)
LINE = (226, 232, 240)
MAINT = (13, 148, 136)  # teal for maintenance
REPAIR = (220, 38, 38)  # red for repairs


def _repo_dir(name: str) -> Path:
    """Locate /icons or /brand — repo, Docker or desktop app."""
    return asset_path(name)


_REPLACEMENTS = {"—": "-", "–": "-", "‘": "'", "’": "'", "“": '"', "”": '"',
                 "•": "-", "…": "...", " ": " ", "→": "->"}


def safe(text) -> str:
    """The built-in PDF fonts only cover Latin-1; replace other characters."""
    text = "" if text is None else str(text)
    for a, b in _REPLACEMENTS.items():
        text = text.replace(a, b)
    return text.encode("latin-1", "replace").decode("latin-1")


def money(cents: int | None, currency: str = "USD") -> str:
    """1234567 -> '$12,345.67' (other currencies get their code)."""
    if cents is None:
        cents = 0  # no cost entered shows as $0.00
    symbol = "$" if currency == "USD" else currency + " "
    return f"{symbol}{cents / 100:,.2f}"


def _svg_icon(key: str, color: tuple[int, int, int]) -> io.BytesIO | None:
    """Load a type icon and color it (fpdf2 doesn't know 'currentColor')."""
    path = _repo_dir("icons") / "types" / f"{key}.svg"
    if not path.is_file():
        path = _repo_dir("icons") / "types" / "custom.svg"
    if not path.is_file():
        return None
    hexcolor = "#%02x%02x%02x" % color
    svg = path.read_text(encoding="utf-8").replace("currentColor", hexcolor)
    svg = re.sub(r'<svg ', '<svg width="24" height="24" ', svg, count=1) if 'width="' not in svg else svg
    return io.BytesIO(svg.encode("utf-8"))


class HistoryPDF(FPDF):
    """FPDF with our footer (page numbers + app name)."""

    vehicle_title = ""

    def footer(self) -> None:
        self.set_y(-12)
        self.set_font("Helvetica", "", 8)
        self.set_text_color(*MUTED)
        self.cell(0, 6, safe(f"Ratchet Wrench - {self.vehicle_title}"), align="L")
        self.cell(0, 6, f"Page {self.page_no()} of {{nb}}", align="R")


def build_report(db: Session, user: User, vehicle: Vehicle, currency: str = "USD", unit: str = "mi") -> bytes:
    """Create the PDF and return its bytes."""
    unit = vehicle.mileage_unit or unit
    specs = db.scalars(select(VehicleSpec).where(VehicleSpec.vehicle_id == vehicle.id, VehicleSpec.deleted_at.is_(None))).all()
    entries = db.scalars(
        select(Entry).where(Entry.vehicle_id == vehicle.id, Entry.deleted_at.is_(None)).order_by(Entry.date, Entry.mileage)
    ).all()
    photos: dict[str, list[Attachment]] = {}
    for att in db.scalars(
        select(Attachment).where(Attachment.vehicle_id == vehicle.id, Attachment.deleted_at.is_(None), Attachment.has_file.is_(True))
    ).all():
        if att.entry_id and att.content_type in ("image/jpeg", "image/png"):
            photos.setdefault(att.entry_id, []).append(att)

    title = vehicle.nickname or " ".join(str(x) for x in (vehicle.year, vehicle.make, vehicle.model) if x)
    full_name = " ".join(str(x) for x in (vehicle.year, vehicle.make, vehicle.model, vehicle.trim) if x)

    pdf = HistoryPDF(format="Letter")
    pdf.vehicle_title = title
    pdf.set_auto_page_break(True, margin=18)
    pdf.set_margins(16, 16, 16)
    pdf.alias_nb_pages()
    pdf.add_page()
    width = pdf.w - pdf.l_margin - pdf.r_margin

    # ---- 1. Header ------------------------------------------------------
    logo = _repo_dir("brand") / "logo-mark.svg"
    if logo.is_file():
        pdf.image(str(logo), x=pdf.l_margin, y=14, w=14, h=14)
    pdf.set_xy(pdf.l_margin + 18, 14)
    pdf.set_font("Helvetica", "B", 18)
    pdf.set_text_color(*INK)
    pdf.cell(0, 8, "Vehicle History Report", new_x="LEFT", new_y="NEXT")
    pdf.set_font("Helvetica", "", 10)
    pdf.set_text_color(*MUTED)
    pdf.cell(0, 6, safe(f"{title}  -  generated {date.today().strftime('%B %d, %Y')}"), new_x="LMARGIN", new_y="NEXT")
    pdf.ln(4)
    pdf.set_draw_color(*BRAND)
    pdf.set_line_width(0.8)
    pdf.line(pdf.l_margin, pdf.get_y(), pdf.l_margin + width, pdf.get_y())
    pdf.ln(5)

    # ---- 2. Summary -----------------------------------------------------
    maint_total = sum(e.cost_cents or 0 for e in entries if e.kind == "maintenance")
    repair_total = sum(e.cost_cents or 0 for e in entries if e.kind == "repair")
    summary = [
        ("Vehicle", full_name or "-"),
        ("Nickname", vehicle.nickname or "-"),
        ("Type", "Motorcycle" if vehicle.kind == "motorcycle" else "Car"),
        ("VIN", vehicle.vin or "-"),
        ("Current mileage", f"{vehicle.current_mileage:,} {unit}" if vehicle.current_mileage is not None else "-"),
        ("Records", f"{sum(e.kind == 'maintenance' for e in entries)} maintenance, {sum(e.kind == 'repair' for e in entries)} repair"),
        ("Maintenance cost", money(maint_total, currency)),
        ("Repair cost", money(repair_total, currency)),
        ("Total cost", money(maint_total + repair_total, currency)),
    ]
    pdf.set_font("Helvetica", "B", 13)
    pdf.set_text_color(*INK)
    pdf.cell(0, 8, "Summary", new_x="LMARGIN", new_y="NEXT")
    col = width / 2
    for i, (label, value) in enumerate(summary):
        x = pdf.l_margin + (i % 2) * col
        if i % 2 == 0 and i:
            pdf.ln(7)
        pdf.set_x(x)
        pdf.set_font("Helvetica", "", 9)
        pdf.set_text_color(*MUTED)
        pdf.cell(36, 7, safe(label))
        pdf.set_font("Helvetica", "B", 10)
        pdf.set_text_color(*INK)
        pdf.cell(col - 36, 7, safe(value))
    pdf.ln(12)

    # ---- 3. Specs -------------------------------------------------------
    sources = load_catalog()["spec_sources"]
    order = {s["key"]: i for i, s in enumerate(load_catalog()["specs"])}
    specs = sorted((s for s in specs if s.value), key=lambda s: order.get(s.key, 999))
    pdf.set_font("Helvetica", "B", 13)
    pdf.cell(0, 8, "Specifications", new_x="LMARGIN", new_y="NEXT")
    if not specs:
        pdf.set_font("Helvetica", "I", 10)
        pdf.set_text_color(*MUTED)
        pdf.cell(0, 7, "No specs recorded yet.", new_x="LMARGIN", new_y="NEXT")
    for i, s in enumerate(specs):
        if i % 2 == 0:
            pdf.set_fill_color(248, 250, 252)
        else:
            pdf.set_fill_color(255, 255, 255)
        pdf.set_font("Helvetica", "", 9)
        pdf.set_text_color(*MUTED)
        pdf.cell(width * 0.42, 7, safe(spec_label(s.key)), fill=True)
        pdf.set_font("Helvetica", "B", 10)
        pdf.set_text_color(*INK)
        pdf.cell(width * 0.36, 7, safe(s.value), fill=True)
        pdf.set_font("Helvetica", "", 8)
        pdf.set_text_color(*MUTED)
        pdf.cell(width * 0.22, 7, safe(sources.get(s.source, s.source)), fill=True, align="R", new_x="LMARGIN", new_y="NEXT")
    pdf.ln(6)

    # ---- 4. Entries -----------------------------------------------------
    pdf.set_font("Helvetica", "B", 13)
    pdf.set_text_color(*INK)
    pdf.cell(0, 8, "Service history", new_x="LMARGIN", new_y="NEXT")
    if not entries:
        pdf.set_font("Helvetica", "I", 10)
        pdf.set_text_color(*MUTED)
        pdf.cell(0, 7, "No maintenance or repair records yet.", new_x="LMARGIN", new_y="NEXT")

    for e in entries:
        if pdf.get_y() > pdf.h - 60:
            pdf.add_page()
        color = REPAIR if e.kind == "repair" else MAINT
        top = pdf.get_y()
        pdf.set_draw_color(*LINE)
        pdf.set_line_width(0.3)
        pdf.line(pdf.l_margin, top, pdf.l_margin + width, top)
        pdf.ln(3)
        y = pdf.get_y()
        icon = _svg_icon(e.type_key, color)
        if icon is not None:
            try:
                pdf.image(icon, x=pdf.l_margin, y=y, w=9, h=9)
            except Exception:
                pass
        pdf.set_xy(pdf.l_margin + 12, y)
        name = e.custom_type if (e.type_key == "custom" and e.custom_type) else type_label(e.type_key)
        pdf.set_font("Helvetica", "B", 12)
        pdf.set_text_color(*INK)
        pdf.cell(width - 50, 6, safe(name))
        pdf.set_font("Helvetica", "B", 8)
        pdf.set_text_color(*color)
        pdf.cell(38, 6, "REPAIR" if e.kind == "repair" else "MAINTENANCE", align="R", new_x="LMARGIN", new_y="NEXT")
        pdf.set_x(pdf.l_margin + 12)
        pdf.set_font("Helvetica", "", 9)
        pdf.set_text_color(*MUTED)
        who = f"Shop: {e.shop_name}" if e.performed_by == "shop" and e.shop_name else ("Shop" if e.performed_by == "shop" else "DIY")
        meta = [e.date.strftime("%b %d, %Y"), f"{e.mileage:,} {unit}" if e.mileage is not None else None,
                money(e.cost_cents or 0, currency), who]
        pdf.cell(0, 5, safe("   |   ".join(m for m in meta if m)), new_x="LMARGIN", new_y="NEXT")
        pdf.ln(1)
        text_x = pdf.l_margin + 12
        for label, value in (("", e.description), ("Parts: ", e.parts_used), ("Notes: ", e.notes)):
            if value:
                pdf.set_x(text_x)
                pdf.set_font("Helvetica", "B" if label else "", 9.5)
                pdf.set_text_color(*INK)
                pdf.multi_cell(width - 12, 5, safe(label + value), new_x="LMARGIN", new_y="NEXT")
        # Photos, two per row.
        pics = photos.get(e.id, [])
        if pics:
            pdf.ln(2)
            pic_w = (width - 12 - 6) / 2
            for i, att in enumerate(pics):
                ratio = (att.height / att.width) if att.width and att.height else 0.75
                pic_h = min(pic_w * ratio, 70)
                w = pic_h / ratio
                if i % 2 == 0:
                    if pdf.get_y() + pic_h > pdf.h - 20:
                        pdf.add_page()
                    row_y = pdf.get_y()
                x = text_x + (i % 2) * (pic_w + 6)
                try:
                    pdf.image(str(file_path(att.stored_name)), x=x, y=row_y, w=w, h=pic_h)
                except Exception:
                    pass
                if i % 2 == 1 or i == len(pics) - 1:
                    pdf.set_y(row_y + pic_h + 3)
        pdf.ln(3)

    out = pdf.output()
    return bytes(out)
