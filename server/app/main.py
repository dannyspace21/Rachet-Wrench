"""
main.py — starts the Ratchet Wrench server and connects all the pieces.

What happens here:
  * every API router (auth, records, attachments, vehicle tools, sync) is
    plugged in under /api
  * security headers are added to EVERY response (see add_security_headers)
  * oversized requests are cut off early (BodySizeLimit)
  * CORS is closed by default — only the built-in web app may call the API
  * the interactive API documentation lives at /api/docs and requires login
  * the web app (plain HTML/CSS/JS in /web) is served at /
  * on first start, the admin account from .env is created (and optional
    sample data is added)
  * if ntfy is configured, a background task checks reminders
"""

from __future__ import annotations

import asyncio
import logging
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import Depends, FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import HTMLResponse, JSONResponse, RedirectResponse
from fastapi.staticfiles import StaticFiles
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from .catalog import load_catalog
from .config import asset_path, get_settings
from .database import get_db, init_db, session_scope
from .models import User
from .routers import attachments, auth, items, sync, vehicles
from .security import current_user, hash_password, user_from_cookie

log = logging.getLogger("ratchetwrench")
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")


def _repo_path(name: str) -> Path:
    """Find a top-level folder (web, icons, brand) — repo, Docker or desktop app."""
    return asset_path(name)


STATIC_DIR = Path(__file__).with_name("static")

# The Content-Security-Policy tells the browser to load scripts, styles,
# images and data ONLY from this server.  No inline scripts, no third-party
# CDNs, no framing by other sites.
CSP = (
    "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' blob: data:; "
    "font-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'none'; "
    "frame-ancestors 'none'; form-action 'self'; manifest-src 'self'; worker-src 'self'"
)
# Swagger UI (the docs page) sets some inline styles, so it gets this one
# relaxation, only on /api/docs.
DOCS_CSP = CSP.replace("style-src 'self'", "style-src 'self' 'unsafe-inline'")


def bootstrap() -> None:
    """Create tables, the first admin account, and optional sample data."""
    settings = get_settings()
    init_db()
    settings.upload_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
    with session_scope() as db:
        if db.scalar(select(func.count()).select_from(User)) == 0:
            if len(settings.admin_password) < 10:
                if settings.setup_token:
                    log.info("No account yet: waiting for first-run setup in the browser.")
                else:
                    log.warning("No users exist yet. Set RW_ADMIN_PASSWORD (10+ characters) in .env and restart.")
                return
            db.add(User(username=settings.admin_username.strip().lower(),
                        password_hash=hash_password(settings.admin_password), is_admin=True))
            db.commit()
            log.info("Created admin user '%s'.", settings.admin_username)
        if settings.seed_sample_data:
            from .seed import seed_sample_data

            seed_sample_data(db)
        # Specs saved before "specs are also parts" existed get their parts once.
        from .spec_parts import backfill

        if added := backfill(db):
            log.info("Added %d parts from existing specs.", added)


@asynccontextmanager
async def lifespan(_app: FastAPI):
    """Runs once at start-up (and clean-up at shutdown)."""
    bootstrap()
    task = None
    if get_settings().ntfy_url:
        from .reminders import reminder_loop

        task = asyncio.create_task(reminder_loop())
    yield
    if task:
        task.cancel()


class BodySizeLimit:
    """Refuses request bodies larger than the biggest allowed upload, so an
    attacker can't fill the disk or memory with a giant request."""

    def __init__(self, app, max_bytes: int):
        self.app = app
        self.max_bytes = max_bytes

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            return await self.app(scope, receive, send)
        headers = dict(scope.get("headers") or [])
        length = headers.get(b"content-length")
        if length is not None and length.isdigit() and int(length) > self.max_bytes:
            response = JSONResponse({"detail": "Request is too large."}, status_code=413)
            return await response(scope, receive, send)
        received = 0

        async def limited_receive():
            nonlocal received
            message = await receive()
            if message["type"] == "http.request":
                received += len(message.get("body", b""))
                if received > self.max_bytes:
                    raise ValueError("request body too large")
            return message

        try:
            await self.app(scope, limited_receive, send)
        except ValueError as exc:
            if str(exc) != "request body too large":
                raise
            response = JSONResponse({"detail": "Request is too large."}, status_code=413)
            await response(scope, receive, send)


def create_app() -> FastAPI:
    """Build the FastAPI application."""
    settings = get_settings()
    app = FastAPI(
        title="Ratchet Wrench API",
        version="1.0.0",
        description="Self-hosted vehicle maintenance & repair tracker. Log in on the web app first to use 'Try it out'.",
        lifespan=lifespan,
        docs_url=None,  # replaced by the login-protected /api/docs below
        redoc_url=None,
        openapi_url=None,
    )

    # --- Security headers on every response ---------------------------
    @app.middleware("http")
    async def add_security_headers(request: Request, call_next):
        response = await call_next(request)
        path = request.url.path
        response.headers.setdefault("Content-Security-Policy", DOCS_CSP if path == "/api/docs" else CSP)
        response.headers["X-Content-Type-Options"] = "nosniff"
        response.headers["X-Frame-Options"] = "DENY"
        response.headers["Referrer-Policy"] = "no-referrer"
        response.headers["Permissions-Policy"] = "camera=(self), microphone=(), geolocation=(), payment=(), usb=()"
        response.headers["Cross-Origin-Opener-Policy"] = "same-origin"
        response.headers.setdefault("Cross-Origin-Resource-Policy", "same-origin")
        # HSTS: browsers remember to use HTTPS only (ignored over plain HTTP).
        response.headers["Strict-Transport-Security"] = "max-age=31536000; includeSubDomains"
        if path.startswith("/api/"):
            response.headers.setdefault("Cache-Control", "no-store")
        return response

    # --- CORS: closed unless you list extra origins in .env -----------
    if settings.cors_origins:
        app.add_middleware(
            CORSMiddleware,
            allow_origins=settings.cors_origins,
            allow_credentials=True,
            allow_methods=["GET", "POST", "PATCH", "PUT", "DELETE"],
            allow_headers=["Authorization", "Content-Type", "X-CSRF-Token"],
        )

    max_body = (max(settings.max_image_mb * 6, settings.max_pdf_mb) + 2) * 1024 * 1024
    app.add_middleware(BodySizeLimit, max_bytes=max_body)

    # --- API routes ------------------------------------------------------
    for r in (auth.router, attachments.router, vehicles.router, sync.router, items.router):
        app.include_router(r)

    @app.get("/api/health", tags=["meta"])
    def health() -> dict:
        """Used by Docker to check the server is alive.  Reveals nothing."""
        return {"status": "ok"}

    @app.get("/api/catalog", tags=["meta"])
    def catalog() -> dict:
        """Maintenance types, spec fields and manufacturer links."""
        return load_catalog()

    # --- Login-protected API documentation --------------------------------
    @app.get("/api/openapi.json", include_in_schema=False)
    def openapi_json(_user: User = Depends(current_user)) -> JSONResponse:
        return JSONResponse(app.openapi())

    @app.get("/api/docs", include_in_schema=False)
    def docs(request: Request, db: Session = Depends(get_db)):
        if user_from_cookie(request, db) is None:
            return RedirectResponse("/#/login?next=/api/docs", status_code=303)
        html = (STATIC_DIR / "docs" / "index.html").read_text(encoding="utf-8")
        return HTMLResponse(html)

    app.mount("/api/static", StaticFiles(directory=STATIC_DIR), name="api-static")

    # --- Shared assets and the web app -----------------------------------
    for name in ("icons", "brand"):
        app.mount(f"/{name}", StaticFiles(directory=_repo_path(name)), name=name)
    app.mount("/", StaticFiles(directory=_repo_path("web"), html=True), name="web")
    return app


app = create_app()
