"""Owner's manual from pasted links or pasted text — and the safety rules
that stop a pasted link from reaching the home network."""

import socket

import pytest

from app import manual_fetch as mf
from app.manual_fetch import LinkError, fetch, html_to_text, parse_links

MANUAL_HTML = """<html><head><title>x</title><style>p{color:red}</style><script>alert('Engine oil 99W-99')</script></head>
<body><h2>Capacities and specifications</h2><table>
<tr><td>Engine oil</td><td>Oil grade 0W-20 API SP</td></tr>
<tr><td>Oil capacity with filter</td><td>4.8 L (5.1 qt)</td></tr>
<tr><td>Brake fluid</td><td>DOT 3</td></tr></table></body></html>"""


def _resolves_to(monkeypatch, address):
    monkeypatch.setattr(socket, "getaddrinfo", lambda host, *a, **k: [(socket.AF_INET, socket.SOCK_STREAM, 6, "", (address, 443))])


def test_html_becomes_text_without_scripts():
    t = html_to_text(MANUAL_HTML)
    assert "Engine oil Oil grade 0W-20 API SP" in t and "99W-99" not in t and "color" not in t


@pytest.mark.parametrize("url, words", [
    ("http://example.com/manual.pdf", "https"),
    ("https://user:pw@example.com/m.pdf", "normal web address"),
    ("https://example.com:8443/m.pdf", "standard https port"),
    ("https://127.0.0.1/m.pdf", "numeric address"),
    ("https://[::1]/m.pdf", "numeric address"),
    ("file:///etc/passwd", "https"),
])
def test_unsafe_links_are_refused(url, words):
    with pytest.raises(LinkError, match=words):
        fetch(url, 1000)


@pytest.mark.parametrize("address", ["127.0.0.1", "192.168.1.1", "10.0.0.5", "169.254.169.254", "100.64.0.1", "::1", "fd00::1"])
def test_names_pointing_home_are_refused(monkeypatch, address):
    _resolves_to(monkeypatch, address)
    with pytest.raises(LinkError, match="private or local"):
        fetch("https://router.example.com/", 1000)


class _FakeResponse:
    def __init__(self, status, headers, body=b""):
        self.status, self._h, self._b = status, headers, body

    def getheader(self, k, default=None):
        return self._h.get(k, default)

    def getheaders(self):
        return list(self._h.items())

    def read(self, n):
        return self._b[:n]


def _fake_web(monkeypatch, routes):
    """routes: {host: (status, headers, body)}; every host resolves to a public address."""
    _resolves_to(monkeypatch, "93.184.215.14")
    seen = []

    class Conn:
        def __init__(self, host, address):
            self.host = host

        def request(self, method, path, headers):
            seen.append(self.host + path)

        def getresponse(self):
            return _FakeResponse(*routes[self.host])

        def close(self):
            pass

    monkeypatch.setattr(mf, "_PinnedHTTPS", Conn)
    return seen


def test_redirect_to_a_private_address_is_refused(monkeypatch):
    _fake_web(monkeypatch, {"cdn.example.com": (302, {"Location": "https://192.168.1.1/admin"})})
    with pytest.raises(LinkError, match="numeric address"):
        fetch("https://cdn.example.com/manual", 1000)


def test_web_page_and_pdf_are_recognised(monkeypatch):
    _fake_web(monkeypatch, {"a.example.com": (200, {"Content-Type": "text/html; charset=utf-8"}, MANUAL_HTML.encode()),
                            "b.example.com": (200, {"Content-Type": "application/octet-stream"}, b"%PDF-1.7 ...")})
    page = fetch("https://a.example.com/specs", 100_000)
    assert page.kind == "text" and "4.8 L (5.1 qt)" in page.text
    assert fetch("https://b.example.com/m", 100_000).kind == "pdf"


def test_too_big_and_sign_in_pages(monkeypatch):
    _fake_web(monkeypatch, {"a.example.com": (200, {"Content-Type": "text/html"}, b"<p>" + b"x" * 5000),
                            "b.example.com": (403, {})})
    with pytest.raises(LinkError, match="too large"):
        fetch("https://a.example.com/", 1000)
    with pytest.raises(LinkError, match="sign in"):
        fetch("https://b.example.com/", 1000)


def test_link_list():
    assert parse_links("https://a/1\n https://a/2 , https://a/1") == ["https://a/1", "https://a/2"]
    with pytest.raises(LinkError):
        parse_links("\n".join(f"https://a/{i}" for i in range(21)))


def test_scan_from_links_and_from_pasted_text(client, admin, monkeypatch):
    from conftest import make_vehicle

    v = make_vehicle(client, admin)
    pages = {"https://owners.example.com/p1": "<p>Engine oil: Oil grade 0W-20</p>",
             "https://owners.example.com/p2": "<p>Brake fluid DOT 4</p>"}
    monkeypatch.setattr(mf, "fetch", lambda url, max_bytes: mf.Fetched(url, "text", text=html_to_text(pages[url])))
    r = client.post(f"/api/vehicles/{v['id']}/manual/scan", headers=admin, data={"urls": "\n".join(pages)})
    assert r.status_code == 200, r.text
    found = {s["key"]: s["options"][0] for s in r.json()["specs"]}
    assert found["oil_type"]["value"] == "0W-20" and found["oil_type"]["where"] == "link 1 (owners.example.com)"
    assert found["brake_fluid_type"]["value"] == "DOT 4" and found["brake_fluid_type"]["where"].startswith("link 2")

    r = client.post(f"/api/vehicles/{v['id']}/manual/scan", headers=admin, data={"text": "Coolant capacity 7.2 L (7.6 qt)"})
    assert r.status_code == 200
    opt = r.json()["specs"][0]["options"][0]
    assert opt["value"] == "7.2 L (7.6 qt)" and opt["where"] == "pasted text"


def test_links_can_be_turned_off(client, admin, monkeypatch):
    from conftest import make_vehicle
    from app.config import get_settings

    v = make_vehicle(client, admin)
    monkeypatch.setenv("RW_MANUAL_LINKS_ENABLED", "false")
    get_settings.cache_clear()
    r = client.post(f"/api/vehicles/{v['id']}/manual/scan", headers=admin, data={"urls": "https://owners.example.com/p1"})
    assert r.status_code == 403


APP_SHELL = b"""<!doctype html><html><head><title></title><script>(function(){ /* router */ })();</script>
<link rel="stylesheet" href="styles.css"></head><body><app-root></app-root><script src="main.js" type="module"></script></body></html>"""
WAF_403 = b"""<HTML><HEAD><TITLE>ERROR: The request could not be satisfied</TITLE></HEAD><BODY><H1>403 ERROR</H1>
Generated by cloudfront (CloudFront)</BODY></HTML>"""


def test_javascript_only_manual_sites_get_clear_advice(monkeypatch):
    """Like ownersmanual.audi.com: the page is an empty app shell, and its data requests are bot-protected."""
    _fake_web(monkeypatch, {"ownersmanual.example.com": (200, {"Content-Type": "text/html"}, APP_SHELL),
                            "api.example.com": (403, {"Content-Type": "text/html", "Server": "CloudFront"}, WAF_403)})
    for url in ("https://ownersmanual.example.com/app/nar/?language=en_US", "https://api.example.com/web/models/"):
        with pytest.raises(LinkError, match="Paste text") as e:
            fetch(url, 100_000)
        assert "only shows its manual inside a web browser" in str(e.value)


def test_several_pasted_sections_are_read_together(client, admin):
    from conftest import make_vehicle

    v = make_vehicle(client, admin)
    r = client.post(f"/api/vehicles/{v['id']}/manual/scan", headers=admin, data={"text": [
        "Technical data\nEngine oil: Oil grade 0W-20 API SP", "Light bulbs\nLow beam H7  High beam H15", ""]})
    assert r.status_code == 200, r.text
    found = {s["key"]: s["options"][0] for s in r.json()["specs"]}
    assert found["oil_type"]["where"] == "pasted section 1"
    assert found["bulb_low_beam"]["value"] == "H7" and found["bulb_low_beam"]["where"] == "pasted section 2"
