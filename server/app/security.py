"""
security.py — everything to do with logging in and proving who you are.

How logins work, in plain English:
  1. You send your username + password.
  2. The password is checked against an Argon2 hash (Argon2 is the current
     recommended password-hashing method; the real password is never stored).
  3. Too many wrong passwords lock the account for a while, and each IP
     address may only try a limited number of times per minute.
  4. On success we create a random 256-bit token.  Only its SHA-256 hash is
     stored.  The token expires after RW_TOKEN_TTL_HOURS.
       - The web app receives it as an HttpOnly cookie (JavaScript can't read
         it, so a malicious script can't steal it).
       - The Android app receives it in the response and keeps it in
         encrypted storage, sending it as "Authorization: Bearer <token>".
  5. Because browsers send cookies automatically, every change made with a
     cookie must ALSO carry a matching X-CSRF-Token header ("double submit"
     protection against cross-site request forgery).
"""

from __future__ import annotations

import hashlib
import secrets
import threading
import time
from collections import defaultdict, deque
from datetime import timedelta

from argon2 import PasswordHasher
from argon2.exceptions import InvalidHashError, VerificationError, VerifyMismatchError
from fastapi import Depends, HTTPException, Request, status
from sqlalchemy import select
from sqlalchemy.orm import Session

from .config import get_settings
from .database import get_db
from .models import AuthToken, User, utcnow

SESSION_COOKIE = "rw_session"
CSRF_COOKIE = "rw_csrf"
CSRF_HEADER = "x-csrf-token"

# Argon2id with the library's recommended (RFC 9106 "low memory") settings.
_hasher = PasswordHasher()

# A pre-computed hash used to spend the same time on unknown usernames as on
# real ones, so attackers can't discover which usernames exist by timing.
_DUMMY_HASH = _hasher.hash("not-a-real-password-" + secrets.token_hex(8))

MIN_PASSWORD_LENGTH = 10


def hash_password(password: str) -> str:
    """Turn a password into an Argon2id hash for storage."""
    return _hasher.hash(password)


def verify_password(password_hash: str, password: str) -> bool:
    """True if the password matches the stored hash.  Never raises."""
    try:
        return _hasher.verify(password_hash, password)
    except (VerifyMismatchError, VerificationError, InvalidHashError):
        return False


def validate_new_password(password: str, username: str = "") -> None:
    """Basic password rules: long enough, not the username."""
    if len(password) < MIN_PASSWORD_LENGTH:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, f"Password must be at least {MIN_PASSWORD_LENGTH} characters.")
    if len(password) > 256:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "Password is too long.")
    if username and password.lower() == username.lower():
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "Password must not be the same as the username.")


def hash_token(token: str) -> str:
    """SHA-256 of a login token (what we store in the database)."""
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


# ---------------------------------------------------------------------------
# Per-IP rate limiting for the login endpoint
# ---------------------------------------------------------------------------
class RateLimiter:
    """Remembers recent attempts per key (IP address) in memory and refuses
    once more than `limit` happened within `window` seconds."""

    def __init__(self) -> None:
        self._hits: dict[str, deque[float]] = defaultdict(deque)
        self._lock = threading.Lock()

    def allow(self, key: str, limit: int, window: float = 60.0) -> bool:
        now = time.monotonic()
        with self._lock:
            hits = self._hits[key]
            while hits and now - hits[0] > window:
                hits.popleft()
            if len(hits) >= limit:
                return False
            hits.append(now)
            # Keep memory bounded if many different IPs show up.
            if len(self._hits) > 10_000:
                self._hits.clear()
            return True

    def reset(self) -> None:
        with self._lock:
            self._hits.clear()


login_limiter = RateLimiter()


def client_ip(request: Request) -> str:
    """The caller's IP address.  Behind a trusted reverse proxy (Caddy) we use
    the first X-Forwarded-For address; otherwise the direct connection."""
    if get_settings().trust_proxy_headers:
        forwarded = request.headers.get("x-forwarded-for", "")
        if forwarded:
            return forwarded.split(",")[0].strip()[:64]
    return request.client.host if request.client else "unknown"


# ---------------------------------------------------------------------------
# Login / logout
# ---------------------------------------------------------------------------
class LoginError(Exception):
    """Raised with a user-friendly message when a login fails."""

    def __init__(self, message: str, status_code: int = status.HTTP_401_UNAUTHORIZED):
        super().__init__(message)
        self.message = message
        self.status_code = status_code


def authenticate(db: Session, username: str, password: str) -> User:
    """Check a username/password, applying lockout rules.  Returns the user
    or raises LoginError.  The message is deliberately the same for 'no such
    user' and 'wrong password' so usernames can't be discovered."""
    settings = get_settings()
    generic = "Incorrect username or password."
    user = db.scalar(select(User).where(User.username == username.strip().lower()))
    if user is None:
        verify_password(_DUMMY_HASH, password)  # equalize timing
        raise LoginError(generic)

    now = utcnow()
    if user.locked_until and user.locked_until > now:
        raise LoginError("Too many failed attempts. Try again later.", status.HTTP_429_TOO_MANY_REQUESTS)

    if not verify_password(user.password_hash, password):
        user.failed_logins += 1
        if user.failed_logins >= settings.login_max_failures:
            user.locked_until = now + timedelta(minutes=settings.login_lockout_minutes)
            user.failed_logins = 0
        db.commit()
        raise LoginError(generic)

    # Success: clear the failure counter and upgrade old hashes if needed.
    user.failed_logins = 0
    user.locked_until = None
    if _hasher.check_needs_rehash(user.password_hash):
        user.password_hash = hash_password(password)
    db.commit()
    return user


def create_token(db: Session, user: User, client: str) -> tuple[str, AuthToken]:
    """Make a new random login token.  Returns (plain token, db row)."""
    token = secrets.token_urlsafe(32)
    row = AuthToken(
        user_id=user.id,
        token_hash=hash_token(token),
        client=client,
        expires_at=utcnow() + timedelta(hours=get_settings().token_ttl_hours),
    )
    db.add(row)
    db.commit()
    return token, row


def _find_token(db: Session, token: str) -> AuthToken | None:
    """Look up a token row that is still valid (not revoked, not expired)."""
    if not token or len(token) > 200:
        return None
    row = db.scalar(select(AuthToken).where(AuthToken.token_hash == hash_token(token)))
    if row is None or row.revoked or row.expires_at <= utcnow():
        return None
    return row


def _token_from_request(request: Request) -> tuple[str, bool]:
    """Find the token on a request.  Returns (token, came_from_cookie)."""
    auth = request.headers.get("authorization", "")
    if auth.lower().startswith("bearer "):
        return auth[7:].strip(), False
    return request.cookies.get(SESSION_COOKIE, ""), True


def _check_csrf(request: Request) -> None:
    """For cookie logins, changes must carry the CSRF header that matches the
    CSRF cookie.  A different website can't read our cookie, so it can't
    forge this header."""
    if request.method in {"GET", "HEAD", "OPTIONS"}:
        return
    cookie = request.cookies.get(CSRF_COOKIE, "")
    header = request.headers.get(CSRF_HEADER, "")
    if not cookie or not header or not secrets.compare_digest(cookie, header):
        raise HTTPException(status.HTTP_403_FORBIDDEN, "Missing or invalid CSRF token.")


def current_token(request: Request, db: Session = Depends(get_db)) -> AuthToken:
    """FastAPI dependency: the valid login token for this request, or 401."""
    token, from_cookie = _token_from_request(request)
    row = _find_token(db, token)
    if row is None:
        raise HTTPException(
            status.HTTP_401_UNAUTHORIZED, "Not logged in.", headers={"WWW-Authenticate": "Bearer"}
        )
    if from_cookie:
        _check_csrf(request)
    # Record activity at most once a minute to keep writes low.
    now = utcnow()
    if row.last_used_at is None or (now - row.last_used_at) > timedelta(minutes=1):
        row.last_used_at = now
        db.commit()
    return row


def current_user(token: AuthToken = Depends(current_token), db: Session = Depends(get_db)) -> User:
    """FastAPI dependency: the logged-in user, or 401."""
    user = db.get(User, token.user_id)
    if user is None:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Not logged in.")
    return user


def admin_user(user: User = Depends(current_user)) -> User:
    """FastAPI dependency: only administrators get past this point."""
    if not user.is_admin:
        raise HTTPException(status.HTTP_403_FORBIDDEN, "Administrator access required.")
    return user


def user_from_cookie(request: Request, db: Session) -> User | None:
    """Used by the API-docs page (a plain GET page, not a JSON API call)."""
    token, _ = _token_from_request(request)
    row = _find_token(db, token)
    return db.get(User, row.user_id) if row else None
