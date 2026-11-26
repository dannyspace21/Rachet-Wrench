"""
config.py — reads every server setting from environment variables.

In Docker these come from the `.env` file (see `.env.example`).  Nothing
secret is ever written in the code itself.  Every setting has a safe default
so the server starts even if a value is missing, EXCEPT the first admin
password, which you must choose yourself.
"""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from functools import lru_cache
from pathlib import Path


def _bool(name: str, default: bool) -> bool:
    """Read a yes/no setting.  Accepts true/false, yes/no, 1/0, on/off."""
    raw = os.environ.get(name)
    if raw is None or raw.strip() == "":
        return default
    return raw.strip().lower() in {"1", "true", "yes", "on"}


def _int(name: str, default: int, minimum: int = 0, maximum: int = 10**9) -> int:
    """Read a whole-number setting and clamp it into a sensible range."""
    raw = os.environ.get(name, "").strip()
    try:
        value = int(raw) if raw else default
    except ValueError:
        value = default
    return max(minimum, min(maximum, value))


def _list(name: str) -> list[str]:
    """Read a comma separated list, e.g. 'https://a.example,https://b.example'."""
    raw = os.environ.get(name, "")
    return [item.strip() for item in raw.split(",") if item.strip()]


@dataclass(frozen=True)
class Settings:
    """All server settings in one place.  Created once by get_settings()."""

    # Where the database file and uploaded files live (a Docker volume).
    data_dir: Path = field(default_factory=lambda: Path(os.environ.get("RW_DATA_DIR", "/data")))

    # First admin account.  Only used when the database has no users yet.
    admin_username: str = field(default_factory=lambda: os.environ.get("RW_ADMIN_USERNAME", "admin"))
    admin_password: str = field(default_factory=lambda: os.environ.get("RW_ADMIN_PASSWORD", ""))

    # How long a login lasts before you must log in again (hours).
    token_ttl_hours: int = field(default_factory=lambda: _int("RW_TOKEN_TTL_HOURS", 24 * 30, 1, 24 * 365))

    # Login protection: lock an account for N minutes after M wrong passwords.
    login_max_failures: int = field(default_factory=lambda: _int("RW_LOGIN_MAX_FAILURES", 5, 1, 100))
    login_lockout_minutes: int = field(default_factory=lambda: _int("RW_LOGIN_LOCKOUT_MINUTES", 15, 1, 24 * 60))
    # Per-IP limit on login attempts per minute (slows down password guessing).
    login_rate_per_minute: int = field(default_factory=lambda: _int("RW_LOGIN_RATE_PER_MINUTE", 10, 1, 1000))

    # Cookies are marked "Secure" (HTTPS only).  Set false ONLY for plain
    # http testing on your own computer.
    cookie_secure: bool = field(default_factory=lambda: _bool("RW_COOKIE_SECURE", True))

    # Extra web origins allowed to call the API from a browser (CORS).
    # Empty = only the built-in web app (same origin) may.  Recommended.
    cors_origins: list[str] = field(default_factory=lambda: _list("RW_CORS_ORIGINS"))

    # Trust X-Forwarded-For from a reverse proxy (Caddy) to learn the real
    # client IP for rate limiting.  Only enable when behind your own proxy.
    trust_proxy_headers: bool = field(default_factory=lambda: _bool("RW_TRUST_PROXY_HEADERS", False))

    # Upload limits.
    max_image_mb: int = field(default_factory=lambda: _int("RW_MAX_IMAGE_MB", 20, 1, 200))
    max_pdf_mb: int = field(default_factory=lambda: _int("RW_MAX_PDF_MB", 40, 1, 500))
    max_pdf_pages: int = field(default_factory=lambda: _int("RW_MAX_PDF_PAGES", 1500, 1, 10000))
    pdf_parse_timeout_s: int = field(default_factory=lambda: _int("RW_PDF_PARSE_TIMEOUT_SECONDS", 30, 1, 600))
    # Owner's manuals run to hundreds of pages: reading one may take longer.
    manual_parse_timeout_s: int = field(default_factory=lambda: _int("RW_MANUAL_PARSE_TIMEOUT_SECONDS", 240, 10, 1200))

    # NHTSA vPIC VIN decoder (free, US government).  Can be switched off.
    nhtsa_enabled: bool = field(default_factory=lambda: _bool("RW_NHTSA_ENABLED", True))
    # Specs > Fill from owner's manual > "Links": the server downloads the
    # https links the owner pastes (public websites only).  False = upload / paste only.
    manual_links_enabled: bool = field(default_factory=lambda: _bool("RW_MANUAL_LINKS_ENABLED", True))
    nhtsa_base_url: str = "https://vpic.nhtsa.dot.gov/api/vehicles"
    # NHTSA recalls by year/make/model (a different NHTSA host).
    nhtsa_recalls_url: str = field(default_factory=lambda: os.environ.get("RW_NHTSA_RECALLS_URL", "https://api.nhtsa.gov").rstrip("/"))

    # Optional paid VIN-spec service (plug-in point; disabled by default).
    vin_spec_provider: str = field(default_factory=lambda: os.environ.get("RW_VIN_SPEC_PROVIDER", "").strip().lower())
    vin_spec_api_key: str = field(default_factory=lambda: os.environ.get("RW_VIN_SPEC_API_KEY", ""))
    vin_spec_api_url: str = field(default_factory=lambda: os.environ.get("RW_VIN_SPEC_API_URL", ""))

    # Optional ntfy push notifications (self-hosted).  Empty = disabled.
    ntfy_url: str = field(default_factory=lambda: os.environ.get("RW_NTFY_URL", "").strip().rstrip("/"))
    ntfy_token: str = field(default_factory=lambda: os.environ.get("RW_NTFY_TOKEN", ""))
    reminder_check_minutes: int = field(default_factory=lambda: _int("RW_REMINDER_CHECK_MINUTES", 60, 1, 24 * 60))

    # In-app bug reports -> GitHub Issues.  The key (a fine-grained GitHub
    # token that can ONLY create issues on this one repository) comes from
    # RW_ISSUE_TOKEN, or from the file the release build bundles into the
    # desktop app.  Empty = the form offers "open on GitHub" instead.
    # TEMPORARY: move this key to a relay service (see docs/BUG-REPORTS.md).
    issue_repo: str = field(default_factory=lambda: os.environ.get("RW_ISSUE_REPO", "dannyspace21/Ratchet-Wrench").strip())
    issue_token: str = field(default_factory=lambda: os.environ.get("RW_ISSUE_TOKEN", "").strip() or _bundled_issue_token())

    # Optional AI vision for hard-to-read door stickers.  OFF by default
    # because it sends the photo to a third party.
    ai_vision_enabled: bool = field(default_factory=lambda: _bool("RW_AI_VISION_ENABLED", False))
    ai_vision_api_key: str = field(default_factory=lambda: os.environ.get("RW_AI_VISION_API_KEY", ""))
    ai_vision_model: str = field(default_factory=lambda: os.environ.get("RW_AI_VISION_MODEL", "claude-opus-5-5"))

    # Server-side text recognition for sticker photos uploaded on the web.
    ocr_enabled: bool = field(default_factory=lambda: _bool("RW_OCR_ENABLED", True))

    # One-time code that allows creating the first account from the web page
    # (used by the desktop app, which generates a random code each start and
    # opens the setup page with it).  Empty = setup page disabled; the first
    # admin then comes from RW_ADMIN_USERNAME / RW_ADMIN_PASSWORD (Docker).
    setup_token: str = field(default_factory=lambda: os.environ.get("RW_SETUP_TOKEN", ""))

    # Fill the database with sample vehicles on first start (demo/screenshots).
    seed_sample_data: bool = field(default_factory=lambda: _bool("RW_SEED_SAMPLE_DATA", False))

    @property
    def db_path(self) -> Path:
        """The SQLite database file."""
        return self.data_dir / "ratchet-wrench.db"

    @property
    def upload_dir(self) -> Path:
        """Folder for uploaded files.  It is NOT inside the web root, so a
        file can never be fetched directly or executed by the web server."""
        return self.data_dir / "uploads"

    @property
    def ai_vision_ready(self) -> bool:
        """AI vision is usable only if switched on AND a key is configured."""
        return self.ai_vision_enabled and bool(self.ai_vision_api_key)


def asset_roots() -> list[Path]:
    """Folders that may hold the shared files (web/, icons/, brand/, catalog/):
    RW_ASSET_DIR (set by the packaged desktop app), the repository root,
    and /app (the Docker image)."""
    roots = []
    if os.environ.get("RW_ASSET_DIR"):
        roots.append(Path(os.environ["RW_ASSET_DIR"]))
    roots += [Path(__file__).resolve().parents[2], Path("/app")]
    return roots


def _bundled_issue_token() -> str:
    """The bug-report key a release build of the desktop app carries (if any)."""
    for root in asset_roots():
        f = root / "bugreport" / "issue_token.txt"
        if f.is_file():
            return f.read_text(encoding="utf-8").strip()
    return ""


def asset_path(*parts: str) -> Path:
    """First existing location of a shared file or folder (or the repo path)."""
    for root in asset_roots():
        candidate = root.joinpath(*parts)
        if candidate.exists():
            return candidate
    return asset_roots()[0].joinpath(*parts)


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    """Build the settings once and reuse them everywhere."""
    return Settings()
