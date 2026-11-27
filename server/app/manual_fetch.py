"""
manual_fetch.py — fetch an owner's manual from links the owner pastes.

Owner's manuals come in many shapes: one PDF, a PDF per chapter, or a web
manual with one page per link.  The owner pastes the link(s); this module
downloads each one and returns its text, one "page" per web page (a PDF is
handed back as bytes for the sandboxed PDF reader).  Nothing is crawled:
only the exact links pasted are fetched, and only when the owner presses
"Read".

Safety (the server sits on a home network, so a pasted link must never be
able to reach the router, NAS or the server itself):
  * https only, standard port 443, no user:password@ in the link;
  * the host name is resolved ONCE and every address must be public — the
    connection then goes to that checked address (so DNS tricks can't swap
    in a private one afterwards), with normal certificate checking for the
    real host name;
  * redirects are followed by hand (max 3) and every hop is checked again;
  * size limit (RW_MAX_PDF_MB), 20 s timeout per request, max 20 links;
  * only PDF and HTML/text are accepted; HTML is turned into plain text
    (scripts and styles dropped) — nothing in it is ever run.
"""

from __future__ import annotations

import http.client
import re
import ipaddress
import socket
import ssl
import urllib.parse
from dataclasses import dataclass
from html.parser import HTMLParser

MAX_LINKS = 20
MAX_REDIRECTS = 3
TIMEOUT_S = 20
USER_AGENT = "RatchetWrench (self-hosted owner's manual reader)"


class LinkError(ValueError):
    """A link that can't or mustn't be fetched (message is shown to the owner)."""


# Shown when a site builds its manual with JavaScript or blocks automatic
# downloads (bot protection) — e.g. Audi's ownersmanual.audi.com.  The
# owner's own browser can open it, so the way in is copying the text.
BROWSER_ONLY = ("{host} only shows its manual inside a web browser (it builds the pages with JavaScript and blocks "
                "automatic downloads), so it can't be read from a link. Open it in your browser, go to the "
                "Technical data / Specifications section, select the text and use “Paste text” — or on your phone, "
                "Share → Ratchet Wrench. You can paste several sections.")


def _looks_blocked(status: int, headers, body: bytes) -> bool:
    """Bot protection (AWS WAF / CloudFront / Cloudflare challenge pages)."""
    if status not in (202, 403, 405, 429, 503):
        return False
    text = body[:4000].decode("latin-1", "replace").lower()
    names = " ".join(f"{k}:{v}" for k, v in (headers or [])).lower()
    return any(w in text or w in names for w in ("awswaf", "cloudfront", "cf-chl", "cloudflare", "captcha",
                                                    "request could not be satisfied", "challenge", "x-amzn-waf"))


@dataclass
class Fetched:
    url: str
    kind: str          # "pdf" or "text"
    data: bytes = b""  # PDF bytes
    text: str = ""     # page text


def parse_links(raw: str) -> list[str]:
    links = []
    for part in (raw or "").replace(",", "\n").split():
        part = part.strip()
        if part and part not in links:
            links.append(part)
    if len(links) > MAX_LINKS:
        raise LinkError(f"Paste up to {MAX_LINKS} links at a time.")
    return links


def _public_address(host: str) -> str:
    """Resolve the host and return one address, refusing anything that
    isn't on the public internet."""
    try:
        infos = socket.getaddrinfo(host, 443, type=socket.SOCK_STREAM)
    except socket.gaierror:
        raise LinkError(f"Couldn't find the website {host}.") from None
    addrs = [i[4][0] for i in infos]
    for a in addrs:
        ip = ipaddress.ip_address(a.split("%")[0])
        if not ip.is_global or ip.is_multicast:
            raise LinkError(f"{host} points to a private or local address, which isn't allowed.")
    return addrs[0]


def _check_url(url: str) -> urllib.parse.SplitResult:
    u = urllib.parse.urlsplit(url.strip())
    if u.scheme != "https":
        raise LinkError("Only https:// links can be read.")
    if not u.hostname or u.username or u.password:
        raise LinkError("That link isn't a normal web address.")
    if u.port not in (None, 443):
        raise LinkError("Only links on the standard https port can be read.")
    try:
        ipaddress.ip_address(u.hostname)
        numeric = True
    except ValueError:
        numeric = False
    if numeric:
        raise LinkError("Use the website's name, not a numeric address.")
    return u


class _PinnedHTTPS(http.client.HTTPSConnection):
    """HTTPS to an already-checked address, verifying the real host's certificate."""

    def __init__(self, host: str, address: str, **kw):
        super().__init__(host, 443, timeout=TIMEOUT_S, context=ssl.create_default_context(), **kw)
        self._address = address

    def connect(self) -> None:
        sock = socket.create_connection((self._address, 443), self.timeout)
        self.sock = self._context.wrap_socket(sock, server_hostname=self.host)


def fetch(url: str, max_bytes: int) -> Fetched:
    for _ in range(MAX_REDIRECTS + 1):
        u = _check_url(url)
        conn = _PinnedHTTPS(u.hostname, _public_address(u.hostname))
        try:
            path = urllib.parse.urlunsplit(("", "", u.path or "/", u.query, ""))
            conn.request("GET", path, headers={"User-Agent": USER_AGENT, "Accept": "application/pdf,text/html;q=0.9,text/plain;q=0.8"})
            res = conn.getresponse()
            if res.status in (301, 302, 303, 307, 308):
                loc = res.getheader("Location")
                if not loc:
                    raise LinkError("The website redirected without saying where.")
                url = urllib.parse.urljoin(url, loc)
                continue
            if res.status != 200 and _looks_blocked(res.status, res.getheaders(), res.read(4000)):
                raise LinkError(BROWSER_ONLY.format(host=u.hostname))
            if res.status != 200:
                raise LinkError(f"The website answered {res.status} for {u.hostname}{u.path[:60]}." +
                                (" It may need you to sign in — download the PDF and upload it instead." if res.status in (401, 403) else ""))
            data = res.read(max_bytes + 1)
            if len(data) > max_bytes:
                raise LinkError("That file is too large.")
            ctype = (res.getheader("Content-Type") or "").split(";")[0].strip().lower()
        except (OSError, http.client.HTTPException, ssl.SSLError) as e:
            if isinstance(e, LinkError):
                raise
            raise LinkError(f"Couldn't download from {u.hostname}.") from None
        finally:
            conn.close()
        if data[:5] == b"%PDF-":
            return Fetched(url, "pdf", data=data)
        if ctype in ("text/html", "application/xhtml+xml", "text/plain") or data.lstrip()[:1] == b"<":
            text = data.decode(_charset(res.getheader("Content-Type")), errors="replace")
            plain = html_to_text(text) if ctype != "text/plain" else text
            # An empty "app shell" (the real content is loaded by JavaScript).
            if ctype != "text/plain" and len(plain) < 100 and re.search(r"<script\b", text, re.I):
                raise LinkError(BROWSER_ONLY.format(host=u.hostname))
            return Fetched(url, "text", text=plain)
        raise LinkError(f"{u.hostname} sent something that isn't a PDF or a web page.")
    raise LinkError("Too many redirects.")


def _charset(ctype: str | None) -> str:
    for part in (ctype or "").split(";")[1:]:
        k, _, v = part.strip().partition("=")
        if k.lower() == "charset" and v:
            try:
                "".encode(v.strip('"'))
                return v.strip('"')
            except LookupError:
                break
    return "utf-8"


class _Text(HTMLParser):
    BLOCK = {"p", "div", "br", "tr", "li", "h1", "h2", "h3", "h4", "h5", "h6", "table", "section", "article", "dt", "dd"}
    SKIP = {"script", "style", "noscript", "template", "svg", "head"}

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.out: list[str] = []
        self.skip = 0

    def handle_starttag(self, tag, attrs):
        if tag in self.SKIP:
            self.skip += 1
        elif tag in self.BLOCK:
            self.out.append("\n")
        elif tag in ("td", "th"):
            self.out.append(" ")

    def handle_endtag(self, tag):
        if tag in self.SKIP and self.skip:
            self.skip -= 1
        elif tag in self.BLOCK:
            self.out.append("\n")

    def handle_data(self, data):
        if not self.skip:
            self.out.append(data)


def html_to_text(html: str) -> str:
    """Visible text of a web page, one line per paragraph / table row."""
    p = _Text()
    p.feed(html)
    p.close()
    lines = [" ".join(ln.split()) for ln in "".join(p.out).splitlines()]
    return "\n".join(ln for ln in lines if ln)
