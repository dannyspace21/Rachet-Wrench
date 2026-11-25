"""
File-upload security tests: real type checking, size limits, EXIF/GPS
removal, HEIC conversion, random names, safe download headers, PDF limits.
"""

import io

import pytest
from conftest import make_vehicle
from fpdf import FPDF
from PIL import Image


def _jpeg_with_gps() -> bytes:
    """A JPEG carrying EXIF data, including a GPS block and a camera model."""
    img = Image.new("RGB", (64, 48), (10, 120, 200))
    exif = Image.Exif()
    exif[0x0110] = "SecretPhone 9"  # camera model
    exif[0x0112] = 6  # orientation: rotate 90
    gps = {1: "N", 2: (40.0, 26.0, 46.0), 3: "W", 4: (79.0, 58.0, 56.0)}
    exif[0x8825] = gps
    buf = io.BytesIO()
    img.save(buf, "JPEG", exif=exif.tobytes())
    return buf.getvalue()


def _pdf(pages: int = 1) -> bytes:
    pdf = FPDF()
    for i in range(pages):
        pdf.add_page()
        pdf.set_font("Helvetica", size=12)
        pdf.cell(0, 10, f"Page {i + 1}")
    return bytes(pdf.output())


def _upload(client, headers, vehicle_id, data, name="file.jpg", category="photo", ctype="image/jpeg"):
    return client.post("/api/attachments", headers=headers, data={"vehicle_id": vehicle_id, "category": category},
                       files={"file": (name, data, ctype)})


def test_exif_and_gps_are_stripped(client, admin, app_env):
    v = make_vehicle(client, admin)
    original = _jpeg_with_gps()
    assert b"SecretPhone" in original
    r = _upload(client, admin, v["id"], original)
    assert r.status_code == 201, r.text
    att = r.json()
    # Orientation was applied to the pixels (64x48 rotated -> 48x64).
    assert (att["width"], att["height"]) == (48, 64)
    data = client.get(f"/api/attachments/{att['id']}/file", headers=admin).content
    assert b"SecretPhone" not in data
    with Image.open(io.BytesIO(data)) as img:
        assert len(img.getexif()) == 0
        assert "exif" not in img.info
    # The stored file name is random, not the uploaded name.
    stored = list((app_env / "data" / "uploads").rglob("*.jpg"))
    assert stored and all("file" not in p.name for p in stored)


def test_wrong_content_is_rejected_whatever_the_extension(client, admin):
    v = make_vehicle(client, admin)
    for payload, name in ((b"<script>alert(1)</script>", "evil.jpg"), (b"MZ\x90\x00binary", "photo.png"),
                          (b"<?php system($_GET['c']); ?>", "x.pdf"), (b"GIF89a....", "a.gif")):
        r = _upload(client, admin, v["id"], payload, name)
        assert r.status_code == 415, (name, r.text)


def test_truncated_image_rejected(client, admin):
    v = make_vehicle(client, admin)
    data = _jpeg_with_gps()[:200]
    assert _upload(client, admin, v["id"], data).status_code == 400


def test_png_kept_as_png_without_text_chunks(client, admin):
    v = make_vehicle(client, admin)
    from PIL import PngImagePlugin

    info = PngImagePlugin.PngInfo()
    info.add_text("Comment", "private note")
    buf = io.BytesIO()
    Image.new("RGBA", (20, 20), (0, 0, 0, 0)).save(buf, "PNG", pnginfo=info)
    r = _upload(client, admin, v["id"], buf.getvalue(), "x.png", ctype="image/png")
    assert r.status_code == 201
    data = client.get(f"/api/attachments/{r.json()['id']}/file", headers=admin).content
    assert data.startswith(b"\x89PNG") and b"private note" not in data


def test_heic_is_converted_to_jpeg(client, admin):
    pillow_heif = pytest.importorskip("pillow_heif")
    v = make_vehicle(client, admin)
    img = Image.new("RGB", (32, 32), (0, 200, 0))
    heif = pillow_heif.from_pillow(img)
    buf = io.BytesIO()
    heif.save(buf, quality=80)
    r = _upload(client, admin, v["id"], buf.getvalue(), "IMG_0001.HEIC", ctype="image/heic")
    assert r.status_code == 201, r.text
    assert r.json()["content_type"] == "image/jpeg"


def test_size_limit(client, admin, monkeypatch):
    from app import config

    monkeypatch.setenv("RW_MAX_IMAGE_MB", "1")
    monkeypatch.setenv("RW_MAX_PDF_MB", "1")
    config.get_settings.cache_clear()
    v = make_vehicle(client, admin)
    big = b"\xff\xd8\xff" + b"\x00" * (2 * 1024 * 1024)
    assert _upload(client, admin, v["id"], big).status_code == 413


def test_decompression_bomb_rejected(client, admin):
    v = make_vehicle(client, admin)
    buf = io.BytesIO()
    Image.new("1", (12000, 12000)).save(buf, "PNG")  # tiny file, 144 megapixels
    r = _upload(client, admin, v["id"], buf.getvalue(), "bomb.png", ctype="image/png")
    assert r.status_code == 400


def test_pdf_receipt_upload_and_download_headers(client, admin):
    v = make_vehicle(client, admin)
    r = _upload(client, admin, v["id"], _pdf(2), "receipt.pdf", "receipt", "application/pdf")
    assert r.status_code == 201, r.text
    assert r.json()["page_count"] == 2
    d = client.get(f"/api/attachments/{r.json()['id']}/file", headers=admin)
    assert d.headers["content-type"] == "application/pdf"
    assert d.headers["content-disposition"].startswith("attachment")
    assert d.headers["x-content-type-options"] == "nosniff"
    assert "sandbox" in d.headers["content-security-policy"]


def test_pdf_page_limit_and_garbage_pdf(client, admin, monkeypatch):
    from app import config

    v = make_vehicle(client, admin)
    assert _upload(client, admin, v["id"], b"%PDF-1.7\nnot really a pdf", "x.pdf", "manual", "application/pdf").status_code == 400
    monkeypatch.setenv("RW_MAX_PDF_PAGES", "3")
    config.get_settings.cache_clear()
    assert _upload(client, admin, v["id"], _pdf(5), "big.pdf", "manual", "application/pdf").status_code == 400


def test_category_restrictions(client, admin):
    v = make_vehicle(client, admin)
    # A sticker must be a photo; a manual must be a PDF.
    assert _upload(client, admin, v["id"], _pdf(), "s.pdf", "sticker", "application/pdf").status_code == 415
    assert _upload(client, admin, v["id"], _jpeg_with_gps(), "m.jpg", "manual").status_code == 415


def test_deleting_attachment_erases_file(client, admin, app_env):
    v = make_vehicle(client, admin)
    att = _upload(client, admin, v["id"], _jpeg_with_gps()).json()
    assert client.delete(f"/api/attachments/{att['id']}", headers=admin).status_code == 200
    assert list((app_env / "data" / "uploads").rglob("*.jpg")) == []
