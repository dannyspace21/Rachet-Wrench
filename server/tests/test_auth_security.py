"""
Security tests for logging in: password storage, lockout, rate limiting,
token expiry, logout, CSRF protection, security headers and the
login-protected API docs.
"""

from datetime import timedelta

from conftest import ADMIN_PASSWORD, login_android, login_web, make_user
from sqlalchemy import select


def test_login_web_sets_secure_cookies(client):
    r = client.post("/api/auth/login", json={"username": "admin", "password": ADMIN_PASSWORD})
    assert r.status_code == 200
    assert "token" not in r.json()  # web never sees the raw token
    cookie_header = "; ".join(r.headers.get_list("set-cookie")).lower()
    assert "rw_session=" in cookie_header and "httponly" in cookie_header and "samesite=strict" in cookie_header


def test_password_is_hashed_with_argon2(client):
    from app.database import session_scope
    from app.models import User

    with session_scope() as db:
        user = db.scalar(select(User).where(User.username == "admin"))
        assert user.password_hash.startswith("$argon2id$")
        assert ADMIN_PASSWORD not in user.password_hash


def test_wrong_password_and_unknown_user_look_the_same(client):
    a = client.post("/api/auth/login", json={"username": "admin", "password": "wrong-password"})
    b = client.post("/api/auth/login", json={"username": "nobody", "password": "wrong-password"})
    assert a.status_code == b.status_code == 401
    assert a.json() == b.json()


def test_lockout_after_repeated_failures(client):
    for _ in range(5):
        client.post("/api/auth/login", json={"username": "admin", "password": "wrong-password"})
    # Even the CORRECT password is refused while locked.
    r = client.post("/api/auth/login", json={"username": "admin", "password": ADMIN_PASSWORD})
    assert r.status_code == 429


def test_per_ip_rate_limit(client, monkeypatch):
    from app import config

    monkeypatch.setenv("RW_LOGIN_RATE_PER_MINUTE", "3")
    config.get_settings.cache_clear()
    codes = [client.post("/api/auth/login", json={"username": "x", "password": "y"}).status_code for _ in range(5)]
    assert codes[:3] == [401, 401, 401]
    assert codes[3:] == [429, 429]


def test_protected_endpoints_require_login(client):
    for path in ("/api/vehicles", "/api/entries", "/api/sync", "/api/auth/me", "/api/openapi.json"):
        assert client.get(path).status_code == 401, path


def test_bearer_token_and_logout_revokes(client):
    h = login_android(client)
    assert client.get("/api/auth/me", headers=h).status_code == 200
    assert client.post("/api/auth/logout", headers=h).status_code == 200
    assert client.get("/api/auth/me", headers=h).status_code == 401


def test_expired_token_is_rejected(client):
    from app.database import session_scope
    from app.models import AuthToken, utcnow

    h = login_android(client)
    with session_scope() as db:
        for t in db.scalars(select(AuthToken)).all():
            t.expires_at = utcnow() - timedelta(seconds=1)
        db.commit()
    assert client.get("/api/auth/me", headers=h).status_code == 401


def test_garbage_token_is_rejected(client):
    assert client.get("/api/auth/me", headers={"Authorization": "Bearer abc"}).status_code == 401
    assert client.get("/api/auth/me", headers={"Authorization": "Bearer " + "x" * 5000}).status_code == 401


def test_cookie_requests_need_csrf_header(client):
    login_web(client)
    body = {"kind": "car", "make": "Kia", "model": "Forte"}
    del client.headers["X-CSRF-Token"]
    assert client.post("/api/vehicles", json=body).status_code == 403
    client.headers["X-CSRF-Token"] = "wrong"
    assert client.post("/api/vehicles", json=body).status_code == 403
    client.headers["X-CSRF-Token"] = client.cookies.get("rw_csrf")
    assert client.post("/api/vehicles", json=body).status_code == 201


def test_change_password_signs_out_other_devices(client):
    phone = login_android(client)
    laptop = login_android(client)
    r = client.post("/api/auth/change-password", headers=laptop,
                    json={"current_password": ADMIN_PASSWORD, "new_password": "a-brand-new-password"})
    assert r.status_code == 200
    assert client.get("/api/auth/me", headers=laptop).status_code == 200
    assert client.get("/api/auth/me", headers=phone).status_code == 401


def test_weak_password_rejected(client, admin):
    r = client.post("/api/auth/users", json={"username": "bob", "password": "short"}, headers=admin)
    assert r.status_code == 400


def test_only_admins_create_users(client, admin):
    bob = make_user(client, admin, "bob")
    r = client.post("/api/auth/users", json={"username": "eve", "password": "long-enough-pass"}, headers=bob)
    assert r.status_code == 403


def test_security_headers_present(client):
    r = client.get("/api/health")
    assert r.headers["x-content-type-options"] == "nosniff"
    assert r.headers["x-frame-options"] == "DENY"
    assert "default-src 'self'" in r.headers["content-security-policy"]
    assert "frame-ancestors 'none'" in r.headers["content-security-policy"]
    assert "max-age" in r.headers["strict-transport-security"]
    assert r.headers["referrer-policy"] == "no-referrer"


def test_cors_closed_by_default(client):
    r = client.get("/api/health", headers={"Origin": "https://evil.example"})
    assert "access-control-allow-origin" not in r.headers


def test_api_docs_require_login(client):
    r = client.get("/api/docs", follow_redirects=False)
    assert r.status_code == 303
    login_web(client)
    r = client.get("/api/docs")
    assert r.status_code == 200 and "swagger-ui" in r.text
    assert client.get("/api/openapi.json").status_code == 200


def test_unknown_fields_rejected(client, admin):
    r = client.post("/api/vehicles", json={"make": "Kia", "is_admin": True}, headers=admin)
    assert r.status_code == 422


def test_javascript_links_rejected(client, admin):
    r = client.post("/api/vehicles", json={"make": "Kia", "parts_url": "javascript:alert(1)"}, headers=admin)
    assert r.status_code == 422


def test_oversized_request_body_rejected(client, admin):
    big = b"x" * (200 * 1024 * 1024)
    r = client.post("/api/vehicles", content=big, headers={**admin, "Content-Type": "application/json"})
    assert r.status_code == 413


def test_cross_site_simple_form_posts_are_rejected(client):
    """A malicious website can send text/plain or form posts without a CORS
    preflight; the API must only accept real JSON."""
    body = '{"username":"admin","password":"correct-horse-battery"}'
    for ctype in ("text/plain", "application/x-www-form-urlencoded"):
        r = client.post("/api/auth/login", content=body, headers={"Content-Type": ctype})
        assert r.status_code == 422, ctype


def test_first_run_setup_needs_code_and_only_works_once(app_env, monkeypatch):
    """Desktop first-run setup: requires the one-time code, only while there
    are no accounts, and never when the server has no setup code at all."""
    from fastapi.testclient import TestClient

    from app import config
    from app.main import create_app

    monkeypatch.setenv("RW_ADMIN_PASSWORD", "")
    monkeypatch.setenv("RW_SETUP_TOKEN", "s" * 32)
    config.get_settings.cache_clear()
    with TestClient(create_app()) as c:
        assert c.get("/api/auth/setup-status").json() == {"needs_setup": True}
        bad = c.post("/api/auth/setup", json={"token": "x" * 32, "username": "dan", "password": "a-long-password"})
        assert bad.status_code == 403
        weak = c.post("/api/auth/setup", json={"token": "s" * 32, "username": "dan", "password": "short"})
        assert weak.status_code == 400
        ok = c.post("/api/auth/setup", json={"token": "s" * 32, "username": "dan", "password": "a-long-password"})
        assert ok.status_code == 200 and ok.json()["user"]["is_admin"]
        assert "rw_session" in c.cookies
        again = c.post("/api/auth/setup", json={"token": "s" * 32, "username": "eve", "password": "a-long-password"})
        assert again.status_code == 409
        assert c.get("/api/auth/setup-status").json() == {"needs_setup": False}


def test_setup_disabled_without_code(client):
    assert client.get("/api/auth/setup-status").json() == {"needs_setup": False}
    r = client.post("/api/auth/setup", json={"token": "x" * 32, "username": "eve", "password": "a-long-password"})
    assert r.status_code in (403, 409)


# ---------------------------------------------------------------------------
# Phone pairing by QR code
# ---------------------------------------------------------------------------
def test_pairing_code_connects_a_phone_once(client, admin):
    from urllib.parse import parse_qs, urlparse

    r = client.post("/api/auth/pair", headers=admin, json={"address": "http://192.168.1.20:8077"})
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["qr_svg"].startswith("data:image/svg+xml")
    q = parse_qs(urlparse(body["payload"]).query)
    assert body["payload"].startswith("ratchetwrench://pair?")
    assert q["server"] == ["http://192.168.1.20:8077"]
    code = q["code"][0]

    claimed = client.post("/api/auth/pair/claim", json={"code": code})
    assert claimed.status_code == 200, claimed.text
    token = claimed.json()["token"]
    assert client.get("/api/auth/me", headers={"Authorization": f"Bearer {token}"}).status_code == 200

    # One use only.
    assert client.post("/api/auth/pair/claim", json={"code": code}).status_code == 401
    # Wrong code.
    assert client.post("/api/auth/pair/claim", json={"code": "x" * 32}).status_code == 401


def test_pairing_needs_a_signed_in_user_and_expires(client, admin):
    from datetime import timedelta

    assert client.post("/api/auth/pair", json={}).status_code == 401
    assert client.post("/api/auth/pair", headers=admin, json={"address": "javascript:alert(1)"}).status_code == 400
    from urllib.parse import parse_qs, urlparse

    payload = client.post("/api/auth/pair", headers=admin, json={}).json()["payload"]
    code = parse_qs(urlparse(payload).query)["code"][0]
    # Make it expire.
    from app.database import get_engine
    from app.models import PairingCode, utcnow
    from sqlalchemy.orm import Session

    with Session(get_engine()) as db:
        for row in db.query(PairingCode).all():
            row.expires_at = utcnow() - timedelta(seconds=1)
        db.commit()
    assert client.post("/api/auth/pair/claim", json={"code": code}).status_code == 401
