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
