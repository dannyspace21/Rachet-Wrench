"""
conftest.py — shared set-up for the automated tests.

Each test gets a brand-new, empty server: a temporary data folder, a fresh
database and an admin account.  Helper functions log in as different users
so we can test that people can't see each other's data.
"""

from __future__ import annotations

import os
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

ADMIN_PASSWORD = "correct-horse-battery"


@pytest.fixture()
def app_env(tmp_path, monkeypatch):
    """Point the server at a temporary folder and reset all cached state."""
    monkeypatch.setenv("RW_DATA_DIR", str(tmp_path / "data"))
    monkeypatch.setenv("RW_ADMIN_USERNAME", "admin")
    monkeypatch.setenv("RW_ADMIN_PASSWORD", ADMIN_PASSWORD)
    monkeypatch.setenv("RW_COOKIE_SECURE", "false")
    monkeypatch.setenv("RW_SEED_SAMPLE_DATA", "false")
    monkeypatch.setenv("RW_NTFY_URL", "")
    monkeypatch.setenv("RW_LOGIN_RATE_PER_MINUTE", "1000")
    from app import config, database, security

    config.get_settings.cache_clear()
    database.reset_engine()
    security.login_limiter.reset()
    yield tmp_path
    database.reset_engine()
    config.get_settings.cache_clear()


@pytest.fixture()
def client(app_env):
    """A test web client talking to a fresh server."""
    from fastapi.testclient import TestClient

    from app.main import create_app

    with TestClient(create_app(), base_url="http://testserver") as c:
        yield c


def login_web(c, username="admin", password=ADMIN_PASSWORD):
    """Log in like the web app: cookies + CSRF header for later changes."""
    r = c.post("/api/auth/login", json={"username": username, "password": password})
    assert r.status_code == 200, r.text
    c.headers["X-CSRF-Token"] = c.cookies.get("rw_csrf")
    return r.json()


def login_android(c, username="admin", password=ADMIN_PASSWORD) -> dict:
    """Log in like the phone app: returns an Authorization header."""
    r = c.post("/api/auth/login", json={"username": username, "password": password, "client": "android"})
    assert r.status_code == 200, r.text
    return {"Authorization": f"Bearer {r.json()['token']}"}


def make_user(c, admin_headers, username, password="another-long-password"):
    """Create a second (non-admin) account and return its auth header."""
    r = c.post("/api/auth/users", json={"username": username, "password": password}, headers=admin_headers)
    assert r.status_code == 201, r.text
    return login_android(c, username, password)


def make_vehicle(c, headers, **kw):
    body = {"kind": "car", "make": "Lexus", "model": "RC 350", **kw}
    r = c.post("/api/vehicles", json=body, headers=headers)
    assert r.status_code == 201, r.text
    return r.json()


@pytest.fixture()
def admin(client):
    return login_android(client)


os.environ.setdefault("RW_DATA_DIR", "/tmp/rw-test-unused")
