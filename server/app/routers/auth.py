"""
routers/auth.py — log in, log out, "who am I", change password, manage
users (admins only) and personal settings (theme, ntfy push).
"""

from __future__ import annotations

import secrets

from fastapi import APIRouter, Depends, HTTPException, Request, Response, status
from sqlalchemy import func, select, update
from sqlalchemy.orm import Session

from .. import schemas
from ..config import get_settings
from ..database import get_db
from ..models import AuthToken, User, UserSettings, utcnow
from ..records import serialize
from ..security import (
    CSRF_COOKIE,
    SESSION_COOKIE,
    LoginError,
    admin_user,
    authenticate,
    client_ip,
    create_token,
    current_token,
    current_user,
    hash_password,
    login_limiter,
    validate_new_password,
    verify_password,
)

router = APIRouter(prefix="/api/auth", tags=["auth"])


def get_or_create_settings(db: Session, user: User) -> UserSettings:
    """Each user has one settings row; create it on first use."""
    s = db.get(UserSettings, user.id)
    if s is None:
        s = UserSettings(user_id=user.id)
        db.add(s)
        db.commit()
    return s


def user_out(db: Session, user: User) -> dict:
    s = get_or_create_settings(db, user)
    cfg = get_settings()
    return {
        "id": user.id,
        "username": user.username,
        "is_admin": user.is_admin,
        "settings": {k: v for k, v in serialize(s).items() if k != "user_id"},
        "server": {
            "ntfy_configured": bool(cfg.ntfy_url),
            "ai_vision_available": cfg.ai_vision_ready,
            "vin_decoder_enabled": cfg.nhtsa_enabled,
        },
    }


@router.post("/login")
def login(body: schemas.LoginIn, request: Request, response: Response, db: Session = Depends(get_db)) -> dict:
    """Check username + password.  Web: sets secure cookies.  Android: returns
    the token in the response body (stored encrypted on the phone)."""
    settings = get_settings()
    if not login_limiter.allow(client_ip(request), settings.login_rate_per_minute):
        raise HTTPException(status.HTTP_429_TOO_MANY_REQUESTS, "Too many login attempts. Wait a minute and try again.")
    try:
        user = authenticate(db, body.username, body.password)
    except LoginError as exc:
        raise HTTPException(exc.status_code, exc.message) from None
    token, row = create_token(db, user, body.client)
    result = {"user": user_out(db, user), "expires_at": row.expires_at.isoformat().replace("+00:00", "Z")}
    if body.client == "android":
        result["token"] = token
    else:
        # HttpOnly session cookie + a CSRF cookie our own JavaScript can read
        # (it copies it into the X-CSRF-Token header).
        _set_web_cookies(response, token)
    return result


def _set_web_cookies(response: Response, token: str) -> None:
    settings = get_settings()
    max_age = settings.token_ttl_hours * 3600
    response.set_cookie(SESSION_COOKIE, token, max_age=max_age, httponly=True,
                        secure=settings.cookie_secure, samesite="strict", path="/")
    response.set_cookie(CSRF_COOKIE, secrets.token_urlsafe(24), max_age=max_age, httponly=False,
                        secure=settings.cookie_secure, samesite="strict", path="/")


@router.post("/pair")
def pair_start(body: schemas.PairStartIn, request: Request, user: User = Depends(current_user),
               db: Session = Depends(get_db)) -> dict:
    """Make a one-time QR code that connects a phone to this account without
    typing the address, username or password.  Valid for 10 minutes, once."""
    import re

    import segno

    from ..netinfo import lan_addresses
    from ..security import create_pairing_code, pairing_payload

    address = (body.address or "").strip().rstrip("/")
    if address and not re.match(r"^https?://[A-Za-z0-9.\-\[\]:]+$", address):
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "Enter the address as http://IP:port or https://name.")
    if not address:
        port = request.url.port
        guesses = lan_addresses()
        host = guesses[0] if guesses else (request.url.hostname or "localhost")
        address = f"{request.url.scheme}://{host}" + (f":{port}" if port else "")
    code, row = create_pairing_code(db, user)
    payload = pairing_payload(address, code)
    qr = segno.make(payload, error="m")
    return {
        "address": address,
        "expires_at": row.expires_at.isoformat().replace("+00:00", "Z"),
        "payload": payload,
        "qr_svg": qr.svg_data_uri(scale=6, border=2),
    }


@router.post("/pair/claim")
def pair_claim(body: schemas.PairClaimIn, request: Request, db: Session = Depends(get_db)) -> dict:
    """The phone sends the code from the QR code and gets its own login."""
    from ..security import claim_pairing_code

    settings = get_settings()
    if not login_limiter.allow(client_ip(request), settings.login_rate_per_minute):
        raise HTTPException(status.HTTP_429_TOO_MANY_REQUESTS, "Too many attempts. Wait a minute and try again.")
    user = claim_pairing_code(db, body.code)
    if user is None:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "This QR code has expired or was already used. Show a new one on your computer.")
    token, row = create_token(db, user, "android")
    return {"user": user_out(db, user), "token": token, "expires_at": row.expires_at.isoformat().replace("+00:00", "Z")}


@router.get("/setup-status")
def setup_status(db: Session = Depends(get_db)) -> dict:
    """Does this server still need its first account created on the web?"""
    no_users = db.scalar(select(func.count()).select_from(User)) == 0
    return {"needs_setup": no_users and bool(get_settings().setup_token)}


@router.post("/setup")
def first_run_setup(body: schemas.SetupIn, request: Request, response: Response, db: Session = Depends(get_db)) -> dict:
    """Create the FIRST account.  Only works while there are no accounts, and
    only with the one-time setup code the desktop app generated (it opens
    this page with the code, so only the person at that computer has it)."""
    settings = get_settings()
    if not login_limiter.allow(client_ip(request), settings.login_rate_per_minute):
        raise HTTPException(status.HTTP_429_TOO_MANY_REQUESTS, "Too many attempts. Wait a minute and try again.")
    if db.scalar(select(func.count()).select_from(User)) != 0:
        raise HTTPException(status.HTTP_409_CONFLICT, "Setup is already complete. Please sign in.")
    if not settings.setup_token or not secrets.compare_digest(body.token, settings.setup_token):
        raise HTTPException(status.HTTP_403_FORBIDDEN, "Invalid setup code. Restart the Ratchet Wrench app and try again.")
    username = body.username.lower()
    validate_new_password(body.password, username)
    user = User(username=username, password_hash=hash_password(body.password), is_admin=True)
    db.add(user)
    db.commit()
    token, row = create_token(db, user, "web")
    _set_web_cookies(response, token)
    return {"user": user_out(db, user)}


@router.post("/logout")
def logout(response: Response, token: AuthToken = Depends(current_token), db: Session = Depends(get_db)) -> dict:
    """End this login (the token stops working immediately)."""
    token.revoked = True
    db.commit()
    response.delete_cookie(SESSION_COOKIE, path="/")
    response.delete_cookie(CSRF_COOKIE, path="/")
    return {"ok": True}


@router.get("/me")
def me(user: User = Depends(current_user), db: Session = Depends(get_db)) -> dict:
    return user_out(db, user)


@router.post("/change-password")
def change_password(body: schemas.ChangePasswordIn, token: AuthToken = Depends(current_token),
                    db: Session = Depends(get_db)) -> dict:
    """Change your password.  All OTHER logins (other devices) are signed out."""
    user = db.get(User, token.user_id)
    if not verify_password(user.password_hash, body.current_password):
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "Current password is incorrect.")
    validate_new_password(body.new_password, user.username)
    user.password_hash = hash_password(body.new_password)
    user.updated_at = utcnow()
    db.execute(update(AuthToken).where(AuthToken.user_id == user.id, AuthToken.id != token.id).values(revoked=True))
    db.commit()
    return {"ok": True}


@router.get("/settings")
def get_user_settings(user: User = Depends(current_user), db: Session = Depends(get_db)) -> dict:
    return user_out(db, user)["settings"]


@router.put("/settings")
def put_user_settings(body: schemas.SettingsIn, user: User = Depends(current_user), db: Session = Depends(get_db)) -> dict:
    s = get_or_create_settings(db, user)
    for key, value in body.model_dump(exclude_unset=True).items():
        if value is not None:
            setattr(s, key, value)
    now = utcnow()
    s.updated_at = now
    s.server_modified_at = now
    db.commit()
    return user_out(db, user)["settings"]


@router.post("/settings/test-push")
def test_push(user: User = Depends(current_user), db: Session = Depends(get_db)) -> dict:
    """Send a test ntfy notification to your topic."""
    from ..reminders import send_ntfy

    s = get_or_create_settings(db, user)
    if not get_settings().ntfy_url:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "The server has no ntfy URL configured (RW_NTFY_URL).")
    if not s.ntfy_topic:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "Choose an ntfy topic first.")
    ok = send_ntfy(s.ntfy_topic, "Ratchet Wrench test", "Push notifications are working.")
    if not ok:
        raise HTTPException(status.HTTP_502_BAD_GATEWAY, "Could not reach the ntfy server.")
    return {"ok": True}


# ---- Admin: user management -------------------------------------------
@router.get("/users")
def list_users(_admin: User = Depends(admin_user), db: Session = Depends(get_db)) -> list[dict]:
    return [{"id": u.id, "username": u.username, "is_admin": u.is_admin} for u in db.scalars(select(User)).all()]


@router.post("/users", status_code=201)
def create_user(body: schemas.CreateUserIn, _admin: User = Depends(admin_user), db: Session = Depends(get_db)) -> dict:
    username = body.username.lower()
    if db.scalar(select(User).where(User.username == username)):
        raise HTTPException(status.HTTP_409_CONFLICT, "That username is taken.")
    validate_new_password(body.password, username)
    user = User(username=username, password_hash=hash_password(body.password), is_admin=body.is_admin)
    db.add(user)
    db.commit()
    return {"id": user.id, "username": user.username, "is_admin": user.is_admin}
