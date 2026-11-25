"""
ocr.py — reading the text on door-jamb sticker photos.

Two ways, chosen by the user on the scan screen:

1. LOCAL (default): Tesseract OCR, an open-source text recognizer installed
   inside the Docker image from Debian's official packages.  The photo never
   leaves your server.  (On Android, Google ML Kit does this on the phone.)

2. AI VISION (optional, OFF by default): sends the photo to Anthropic's
   Claude API for better accuracy on worn or blurry stickers.
   OUTBOUND CONNECTION #3 (documented in SECURITY.md).  It only works if the
   server owner sets RW_AI_VISION_ENABLED=true and RW_AI_VISION_API_KEY in
   .env, and the app shows a clear warning that the photo goes to a third
   party.  The AI is asked only to transcribe/extract what is printed; its
   answer is validated and still goes through the human review screen.
"""

from __future__ import annotations

import base64
import json
try:  # Linux/macOS only (see uploads.py)
    import resource
except ImportError:
    resource = None  # type: ignore[assignment]
import shutil
import subprocess
import tempfile
from pathlib import Path

from PIL import Image

from .config import get_settings
from .sticker_parser import parse_sticker_text
from .uploads import minimal_env

TESSERACT_TIMEOUT_S = 30

# Fields the AI may return — exactly the spec keys a sticker can contain.
AI_FIELDS = [
    "vin", "tire_size_front", "tire_size_rear", "tire_size_spare",
    "tire_pressure_front", "tire_pressure_rear", "tire_pressure_spare",
    "rim_size_front", "rim_size_rear", "paint_code", "paint_color", "trim_code",
    "build_date", "gvwr", "gawr_front", "gawr_rear",
]


def tesseract_available() -> bool:
    """Is the Tesseract program installed on this machine?"""
    return get_settings().ocr_enabled and shutil.which("tesseract") is not None


def _limit_ocr_resources() -> None:  # pragma: no cover - runs in the child
    """Cap Tesseract at 1 GB of memory and 30 s of CPU (defense in depth:
    Debian's Tesseract package has known bugs without fixes yet; see
    SECURITY.md)."""
    resource.setrlimit(resource.RLIMIT_AS, (1024**3, 1024**3))
    resource.setrlimit(resource.RLIMIT_CPU, (TESSERACT_TIMEOUT_S, TESSERACT_TIMEOUT_S + 1))


def ocr_image_file(path: Path) -> str:
    """Run Tesseract on an image and return the text it read.

    Tesseract never sees the uploaded file itself: we decode it with Pillow,
    convert it to a plain grayscale PNG (bigger if small, which also helps
    recognition) and hand Tesseract only that freshly generated file."""
    exe = shutil.which("tesseract")
    if not exe:
        raise RuntimeError("Text recognition (Tesseract) is not installed on this server.")
    with Image.open(path) as src:
        gray = src.convert("L")
    if max(gray.size) < 1600:
        factor = 1600 / max(gray.size)
        gray = gray.resize((int(gray.width * factor), int(gray.height * factor)), Image.Resampling.LANCZOS)
    results = []
    with tempfile.TemporaryDirectory() as tmpdir:
        clean = Path(tmpdir) / "sticker.png"
        gray.save(clean, format="PNG")
        # psm 6 = a block of text; psm 11 = sparse text (suits stickers).
        for psm in ("6", "11"):
            try:
                # Reviewed: fixed argument list, no shell; the only variable
                # part is our own temporary file path.
                proc = subprocess.run(  # noqa: S603  # nosemgrep
                    [exe, str(clean), "stdout", "--psm", psm, "-l", "eng"],
                    capture_output=True,
                    timeout=TESSERACT_TIMEOUT_S,
                    preexec_fn=_limit_ocr_resources if resource is not None else None,  # noqa: PLW1509
                    env=minimal_env(OMP_THREAD_LIMIT="1"),
                    check=False,
                )
            except subprocess.TimeoutExpired:
                continue
            results.append(proc.stdout.decode("utf-8", "replace"))
    return "\n".join(results)


def ai_vision_extract(image_bytes: bytes, media_type: str = "image/jpeg") -> dict[str, str]:
    """Ask Claude to read the sticker.  Returns {spec_key: value}.

    Uses Anthropic's official Python SDK, imported here (not at the top) so
    the library is only loaded when the owner has switched this mode on."""
    settings = get_settings()
    if not settings.ai_vision_ready:
        raise RuntimeError("AI vision mode is not enabled on this server.")
    import anthropic  # official SDK; see DECISIONS.md

    client = anthropic.Anthropic(api_key=settings.ai_vision_api_key, timeout=90.0, max_retries=2)
    schema = {
        "type": "object",
        "properties": {k: {"type": "string"} for k in AI_FIELDS},
        "required": AI_FIELDS,
        "additionalProperties": False,
    }
    prompt = (
        "This photo shows one or more vehicle door-jamb stickers (tire & loading "
        "information placard, certification label, or paint/trim label). "
        "Transcribe ONLY values that are clearly printed on the sticker. "
        "Use an empty string for anything not visible or not legible — never guess. "
        "Formats: tire sizes like '235/40R19 96W'; pressures as the cold PSI number "
        "only (e.g. '35'); rim sizes as inches (e.g. '19'); weights as pounds "
        "number only; build_date as MM/YY; vin as the 17-character VIN."
    )
    try:
        response = client.beta.messages.create(
            model=settings.ai_vision_model,
            max_tokens=2000,
            betas=["server-side-fallback-2026-07-01"],
            fallbacks="default",
            output_config={"effort": "low", "format": {"type": "json_schema", "schema": schema}},
            messages=[{
                "role": "user",
                "content": [
                    {"type": "image", "source": {
                        "type": "base64", "media_type": media_type,
                        "data": base64.standard_b64encode(image_bytes).decode("ascii"),
                    }},
                    {"type": "text", "text": prompt},
                ],
            }],
        )
    except anthropic.AuthenticationError:
        raise RuntimeError("The AI vision API key was rejected.") from None
    except anthropic.RateLimitError:
        raise RuntimeError("The AI vision service is busy; try again in a minute.") from None
    except anthropic.APIStatusError as exc:
        raise RuntimeError(f"The AI vision service returned an error ({exc.status_code}).") from None
    except anthropic.APIConnectionError:
        raise RuntimeError("Could not reach the AI vision service.") from None

    if response.stop_reason == "refusal":
        raise RuntimeError("The AI vision service declined to read this photo.")
    text = next((b.text for b in response.content if getattr(b, "type", "") == "text"), "")
    try:
        data = json.loads(text)
    except json.JSONDecodeError:
        raise RuntimeError("The AI vision service returned an unreadable answer.") from None
    # Keep only known keys with short, non-empty string values.
    return {k: str(v).strip()[:64] for k, v in data.items() if k in AI_FIELDS and isinstance(v, str) and v.strip()}


def extract_from_image(path: Path, use_ai: bool) -> tuple[dict[str, str], str]:
    """Read one sticker photo.  Returns (fields, raw_text_for_debugging)."""
    if use_ai:
        media = "image/png" if path.suffix == ".png" else "image/jpeg"
        return ai_vision_extract(path.read_bytes(), media), ""
    text = ocr_image_file(path)
    return parse_sticker_text(text), text
