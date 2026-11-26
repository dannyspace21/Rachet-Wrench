"""In-app bug reports -> GitHub issues (the GitHub call itself is replaced by a fake)."""

import pytest

from app.config import get_settings
from app.routers import bugs


def _key(monkeypatch, value):
    monkeypatch.setenv("RW_ISSUE_TOKEN", value)
    get_settings.cache_clear()


@pytest.fixture(autouse=True)
def _restore():
    yield
    get_settings.cache_clear()


@pytest.fixture
def github(monkeypatch):
    sent = []
    _key(monkeypatch, "test-token")
    monkeypatch.setattr(bugs, "send_issue", lambda repo, token, title, body: sent.append((repo, token, title, body)) or
                        {"number": 42, "url": f"https://github.com/{repo}/issues/42"})
    bugs._sent.clear()
    return sent


def test_report_becomes_an_issue(client, admin, github):
    assert client.get("/api/bug-report", headers=admin).json()["enabled"] is True
    r = client.post("/api/bug-report", headers=admin, json={
        "summary": "Camera buttons hard to see", "what_happened": "Ping @someone please", "steps": "1. Scan VIN",
        "details": "Browser: test"})
    assert r.status_code == 200 and r.json()["number"] == 42
    repo, token, title, body = github[0]
    assert repo == "dannyspace21/Ratchet-Wrench" and token == "test-token"
    assert title == "[Bug] Camera buttons hard to see"
    assert "@​someone" in body and "## Steps to reproduce" in body and "Browser: test" in body


def test_rate_limited_and_validated(client, admin, github):
    assert client.post("/api/bug-report", headers=admin, json={"summary": "x"}).status_code == 422  # too short
    assert client.post("/api/bug-report", headers=admin, json={"summary": "First report"}).status_code == 200
    assert client.post("/api/bug-report", headers=admin, json={"summary": "Second report"}).status_code == 429


def test_needs_sign_in_and_a_key(client, admin, monkeypatch):
    assert client.post("/api/bug-report", json={"summary": "No login here"}).status_code == 401
    _key(monkeypatch, "")
    assert client.get("/api/bug-report", headers=admin).json()["enabled"] is False
    assert client.post("/api/bug-report", headers=admin, json={"summary": "No key set up"}).status_code == 503
