"""
routers/auth.py — log in, log out, "who am I", change password, manage
users (admins only) and personal settings (theme, ntfy push).
"""

from __future__ import annotations

import secrets

from fastapi import APIRouter, Depends, HTTPException, Request, Response, status
from sqlalchemy import select, update
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
        max_age = settings.token_ttl_hours * 3600
        response.set_cookie(SESSION_COOKIE, token, max_age=max_age, httponly=True,
                            secure=settings.cookie_secure, samesite="strict", path="/")
        # The CSRF cookie is readable by our own JavaScript (not HttpOnly) so
        # it can copy it into the X-CSRF-Token header.
        response.set_cookie(CSRF_COOKIE, secrets.token_urlsafe(24), max_age=max_age, httponly=False,
                            secure=settings.cookie_secure, samesite="strict", path="/")
    return result


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
