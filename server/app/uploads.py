"""
uploads.py — safely accepting files people upload.

Every uploaded file goes through these steps:

 1. SIZE LIMIT — we stop reading as soon as the file is bigger than allowed.
 2. REAL TYPE CHECK — we look at the first bytes of the file ("magic
    numbers"), not the file name, to see what it really is.  Only JPEG, PNG,
    HEIC and PDF are accepted.
 3. IMAGES are fully decoded and then RE-ENCODED from the raw pixels.  This
    throws away everything that isn't the picture itself: EXIF metadata
    (GPS location, phone model, timestamps), embedded thumbnails, comments,
    and anything hidden after the image data.  HEIC photos are converted to
    JPEG so every browser can show them.
 4. PDFs are opened by a separate, sandboxed helper process (pdf_worker.py)
    with memory, CPU-time and wall-clock limits, so a booby-trapped PDF can't
    hang or crash the server.  Page count is limited too.
 5. The file is saved under a RANDOM name in the uploads folder, which lives
    outside the web root.  Files are only ever sent back as downloads with
    a fixed content type — never executed.
"""

from __future__ import annotations

import hashlib
import io
import json
import os
import re
try:  # Linux/macOS: per-process memory & CPU limits for the PDF helper.
    import resource
except ImportError:  # Windows (desktop app): only the wall-clock timeout applies.
    resource = None  # type: ignore[assignment]
import secrets
import subprocess
import sys
import warnings
from dataclasses import dataclass
from pathlib import Path

from fastapi import HTTPException, UploadFile, status
from PIL import Image, ImageOps

from .config import get_settings

try:  # HEIC support (iPhone / many Android photos).
    from pillow_heif import register_heif_opener

    register_heif_opener()
    HEIC_SUPPORTED = True
except ImportError:  # pragma: no cover - the library is in requirements
    HEIC_SUPPORTED = False

# Refuse absurdly large images (a "decompression bomb" is a tiny file that
# expands to billions of pixels and eats all memory).  60 megapixels is far
# above any phone camera.
MAX_PIXELS = 60_000_000
Image.MAX_IMAGE_PIXELS = MAX_PIXELS
warnings.simplefilter("error", Image.DecompressionBombWarning)

THUMB_MAX_SIDE = 480
# Stored files are always named <32 random hex characters>.<jpg|png|pdf>.
STORED_NAME_RE = re.compile(r"^[0-9a-f]{32}\.(jpg|png|pdf)$")
CHUNK = 1024 * 1024


@dataclass
class StoredFile:
    """What we know about a file after it was accepted and saved."""

    stored_name: str
    content_type: str
    size_bytes: int
    sha256: str
    width: int | None = None
    height: int | None = None
    page_count: int | None = None


# ---------------------------------------------------------------------------
# Step 2: what is this file really?
# ---------------------------------------------------------------------------
def sniff_type(head: bytes) -> str | None:
    """Identify a file from its first bytes.  Returns a MIME type or None."""
    if head.startswith(b"\xff\xd8\xff"):
        return "image/jpeg"
    if head.startswith(b"\x89PNG\r\n\x1a\n"):
        return "image/png"
    if head.startswith(b"%PDF-"):
        return "application/pdf"
    # HEIC/HEIF: an ISO media "ftyp" box with a HEIF brand at bytes 4..12.
    if len(head) >= 12 and head[4:8] == b"ftyp":
        brand = head[8:12]
        if brand in {b"heic", b"heix", b"hevc", b"hevx", b"heim", b"heis", b"mif1", b"msf1"}:
            return "image/heic"
    return None


async def read_limited(upload: UploadFile, max_bytes: int) -> bytes:
    """Read an upload into memory, refusing it once it exceeds max_bytes."""
    buf = io.BytesIO()
    total = 0
    while True:
        chunk = await upload.read(CHUNK)
        if not chunk:
            break
        total += len(chunk)
        if total > max_bytes:
            raise HTTPException(
                status.HTTP_413_CONTENT_TOO_LARGE,
                f"File is too large (limit {max_bytes // (1024 * 1024)} MB).",
            )
        buf.write(chunk)
    data = buf.getvalue()
    if not data:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "The file is empty.")
    return data


# ---------------------------------------------------------------------------
# Step 3: images
# ---------------------------------------------------------------------------
def clean_image(data: bytes, sniffed: str) -> tuple[bytes, str, int, int, bytes]:
    """Decode an image, fix its rotation, and re-encode it WITHOUT metadata.

    Returns (clean bytes, content type, width, height, thumbnail jpeg bytes).
    Raises HTTP 400 if the file isn't a valid, reasonably sized image."""
    if sniffed == "image/heic" and not HEIC_SUPPORTED:
        raise HTTPException(status.HTTP_415_UNSUPPORTED_MEDIA_TYPE, "HEIC images are not supported on this server.")
    try:
        with Image.open(io.BytesIO(data)) as probe:
            if probe.width * probe.height > MAX_PIXELS:
                raise HTTPException(status.HTTP_400_BAD_REQUEST, "Image dimensions are too large.")
            # The decoder's idea of the format must agree with the magic bytes.
            expected = {"image/jpeg": "JPEG", "image/png": "PNG", "image/heic": "HEIF"}[sniffed]
            if probe.format != expected:
                raise HTTPException(status.HTTP_400_BAD_REQUEST, "File content does not match its type.")
            probe.load()
            # Apply the camera's rotation flag to the pixels themselves, since
            # we are about to delete the EXIF block that holds that flag.
            img = ImageOps.exif_transpose(probe)
    except HTTPException:
        raise
    except (Image.DecompressionBombError, Image.DecompressionBombWarning):
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "Image dimensions are too large.") from None
    except Exception:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "The image could not be read.") from None

    # Copy ONLY the pixel data into a brand new image: nothing else survives.
    if sniffed == "image/png":
        mode = "RGBA" if img.mode in ("RGBA", "LA", "P") else "RGB"
        pixels = img.convert(mode)
        clean = Image.new(mode, pixels.size)
        clean.paste(pixels)
        out = io.BytesIO()
        clean.save(out, format="PNG", optimize=True)  # no text/EXIF chunks
        content_type = "image/png"
    else:  # JPEG and HEIC both become a clean JPEG
        pixels = img.convert("RGB")
        clean = Image.new("RGB", pixels.size)
        clean.paste(pixels)
        out = io.BytesIO()
        clean.save(out, format="JPEG", quality=88, optimize=True)  # no exif= given
        content_type = "image/jpeg"

    thumb = clean.convert("RGB")
    thumb.thumbnail((THUMB_MAX_SIDE, THUMB_MAX_SIDE))
    tout = io.BytesIO()
    thumb.save(tout, format="JPEG", quality=80)
    return out.getvalue(), content_type, clean.width, clean.height, tout.getvalue()


# ---------------------------------------------------------------------------
# Step 4: PDFs, in a sandboxed helper process
# ---------------------------------------------------------------------------
def _limit_child_resources() -> None:  # pragma: no cover - runs in the child
    """Runs inside the helper process just before it starts: cap its memory
    to 1 GB and its CPU time, so a malicious PDF can't exhaust the server."""
    timeout = get_settings().pdf_parse_timeout_s
    resource.setrlimit(resource.RLIMIT_AS, (1024**3, 1024**3))
    resource.setrlimit(resource.RLIMIT_CPU, (timeout, timeout + 1))
    resource.setrlimit(resource.RLIMIT_NOFILE, (64, 64))


def minimal_env(**extra: str) -> dict[str, str]:
    """The smallest environment a helper process needs.  Windows can't start
    programs without SYSTEMROOT; elsewhere a fixed PATH is enough."""
    if os.name == "nt":
        env = {"SYSTEMROOT": os.environ.get("SYSTEMROOT", r"C:\Windows")}
    else:
        env = {"PATH": "/usr/bin:/bin"}
    env.update(extra)
    return env


def run_pdf_worker(data: bytes, mode: str, max_pages: int, timeout: int | None = None) -> dict:
    """Ask the sandboxed helper to read a PDF.

    mode "info" returns {"pages": N}; mode "text" also returns the text of
    each page.  Any failure (bad PDF, too many pages, timeout) becomes a
    friendly HTTP 400 error."""
    settings = get_settings()
    worker = Path(__file__).with_name("pdf_worker.py")
    if getattr(sys, "frozen", False):
        # Packaged desktop app: there is no separate Python interpreter, so
        # the app's own executable runs the helper (see desktop/launcher.py).
        argv = [sys.executable, "--pdf-worker", mode, str(max_pages)]
    else:
        argv = [sys.executable, "-I", str(worker), mode, str(max_pages)]
    try:
        # Reviewed: fixed argument list (our own script + two numbers we
        # generate), no shell, isolated interpreter (-I), empty environment.
        proc = subprocess.run(  # noqa: S603  # nosemgrep
            argv,
            input=data,
            capture_output=True,
            timeout=timeout or settings.pdf_parse_timeout_s,
            preexec_fn=_limit_child_resources if resource is not None else None,  # noqa: PLW1509
            env=minimal_env(),  # -I + almost-empty environment: no outside influence
            check=False,
        )
    except subprocess.TimeoutExpired:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "The PDF took too long to read and was rejected.") from None
    try:
        result = json.loads(proc.stdout.decode("utf-8") or "{}")
    except (UnicodeDecodeError, json.JSONDecodeError):
        result = {}
    if proc.returncode != 0 or "error" in result or "pages" not in result:
        message = result.get("error") if isinstance(result.get("error"), str) else "The PDF could not be read."
        raise HTTPException(status.HTTP_400_BAD_REQUEST, message)
    return result


# ---------------------------------------------------------------------------
# Step 5: saving
# ---------------------------------------------------------------------------
def _path_for(stored_name: str) -> Path:
    """Where a stored file lives.  The name is always our own random hex, so
    it can't contain '../' or other path tricks; we still double-check."""
    if not STORED_NAME_RE.match(stored_name):
        raise ValueError("invalid stored name")
    base = get_settings().upload_dir
    return base / stored_name[:2] / stored_name


def file_path(stored_name: str) -> Path:
    """Public helper: absolute path to a stored file."""
    return _path_for(stored_name)


def thumb_path(stored_name: str) -> Path:
    """Path to the small preview image saved next to each photo."""
    return _path_for(stored_name).with_name(stored_name + ".thumb.jpg")


def _write(stored_name: str, data: bytes) -> Path:
    """Save bytes under a validated random name."""
    return _write_at(_path_for(stored_name), data)


def _write_at(path: Path, data: bytes) -> Path:
    """Create a brand-new private file at `path` and write the bytes."""
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    # O_EXCL: never overwrite an existing file; 0o600: only our user can read.
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "wb") as fh:
        fh.write(data)
    return path


def delete_stored(stored_name: str) -> None:
    """Remove a stored file and its thumbnail (ignores missing files)."""
    if not stored_name:
        return
    for p in (file_path(stored_name), thumb_path(stored_name)):
        try:
            p.unlink()
        except FileNotFoundError:
            pass


async def accept_upload(upload: UploadFile, allow_pdf: bool = True, allow_images: bool = True) -> StoredFile:
    """The full pipeline above.  Returns details of the saved file."""
    settings = get_settings()
    max_bytes = max(settings.max_image_mb, settings.max_pdf_mb if allow_pdf else 0) * 1024 * 1024
    data = await read_limited(upload, max_bytes)
    return store_bytes(data, allow_pdf=allow_pdf, allow_images=allow_images)


def store_bytes(data: bytes, allow_pdf: bool = True, allow_images: bool = True) -> StoredFile:
    """Validate + clean + save raw bytes (shared by uploads and seeding)."""
    settings = get_settings()
    sniffed = sniff_type(data[:16])
    if sniffed is None or (sniffed == "application/pdf" and not allow_pdf) or (
        sniffed != "application/pdf" and not allow_images
    ):
        allowed = []
        if allow_images:
            allowed.append("JPEG, PNG, HEIC")
        if allow_pdf:
            allowed.append("PDF")
        raise HTTPException(status.HTTP_415_UNSUPPORTED_MEDIA_TYPE, f"Only {' or '.join(allowed)} files are allowed.")

    if sniffed == "application/pdf":
        if len(data) > settings.max_pdf_mb * 1024 * 1024:
            raise HTTPException(status.HTTP_413_CONTENT_TOO_LARGE, f"PDF is too large (limit {settings.max_pdf_mb} MB).")
        info = run_pdf_worker(data, "info", settings.max_pdf_pages)
        stored_name = secrets.token_hex(16) + ".pdf"
        _write(stored_name, data)
        return StoredFile(stored_name, "application/pdf", len(data), hashlib.sha256(data).hexdigest(), page_count=info["pages"])

    if len(data) > settings.max_image_mb * 1024 * 1024:
        raise HTTPException(status.HTTP_413_CONTENT_TOO_LARGE, f"Image is too large (limit {settings.max_image_mb} MB).")
    clean, content_type, width, height, thumb = clean_image(data, sniffed)
    ext = ".png" if content_type == "image/png" else ".jpg"
    stored_name = secrets.token_hex(16) + ext
    _write(stored_name, clean)
    _write_at(thumb_path(stored_name), thumb)
    return StoredFile(stored_name, content_type, len(clean), hashlib.sha256(clean).hexdigest(), width, height)


def safe_display_name(name: str | None) -> str:
    """Keep a cleaned-up copy of the original file name, for display only."""
    if not name:
        return ""
    name = os.path.basename(name.replace("\\", "/"))
    cleaned = "".join(c for c in name if c.isalnum() or c in " ._-()")
    return cleaned.strip()[:120]
