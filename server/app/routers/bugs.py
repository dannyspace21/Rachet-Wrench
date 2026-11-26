"""
routers/bugs.py — "Report a bug" from inside the app (web / desktop).

  GET  /api/bug-report   can reports be sent from here? (is a key set up)
  POST /api/bug-report   file the report as an issue on the project's GitHub

The person fills in a short form; the server turns it into a GitHub issue
(labels: bug, user-report) using a key that can ONLY create issues on this
one repository.  Nothing is sent unless the person presses Submit, and the
form shows exactly what will be sent.

Safety:
  * signed-in users only; at most 1 report a minute and 10 a day per user;
  * length limits on every field; "@" is defused so a report can't ping
    GitHub users; nothing about the user's vehicles or account is added.

TEMPORARY (owner's choice): the key ships inside the release builds.  It is
meant to move to a small relay service — see docs/BUG-REPORTS.md.
"""

from __future__ import annotations

import json
import threading
import time
import urllib.error
import urllib.request
from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, status
from pydantic import Field

from ..config import get_settings
from ..models import User
from ..schemas import Strict
from ..security import current_user

router = APIRouter(prefix="/api/bug-report", tags=["bug reports"])

_lock = threading.Lock()
_sent: dict[str, list[float]] = {}  # user id -> times of recent reports


class BugReportIn(Strict):
    summary: Annotated[str, Field(min_length=5, max_length=200)]
    what_happened: Annotated[str, Field(max_length=4000)] = ""
    steps: Annotated[str, Field(max_length=4000)] = ""
    expected: Annotated[str, Field(max_length=2000)] = ""
    details: Annotated[str, Field(max_length=4000)] = ""  # app/browser info the form showed


def defuse(text: str) -> str:
    """No @mentions (they'd notify GitHub users) and no stray HTML comments."""
    return text.replace("@", "@​").replace("<!--", "&lt;!--")


def issue_body(r: BugReportIn, source: str) -> str:
    parts = [f"**Sent from:** {source}", "", "## What happened", defuse(r.what_happened or r.summary)]
    if r.steps:
        parts += ["", "## Steps to reproduce", defuse(r.steps)]
    if r.expected:
        parts += ["", "## What was expected", defuse(r.expected)]
    if r.details:
        parts += ["", "## Details (added by the app)", "```", r.details.replace("```", "'''"), "```"]
    parts += ["", "_Reported from inside Ratchet Wrench._"]
    return "\n".join(parts)


def send_issue(repo: str, token: str, title: str, body: str, timeout: float = 15.0) -> dict:
    """Create the issue; returns {"number", "url"}.  Raises on failure."""
    data = json.dumps({"title": title, "body": body, "labels": ["bug", "user-report"]}).encode()
    req = urllib.request.Request(f"https://api.github.com/repos/{repo}/issues", data=data, method="POST", headers={
        "Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28", "Content-Type": "application/json", "User-Agent": "RatchetWrench-bug-report"})
    with urllib.request.urlopen(req, timeout=timeout) as resp:  # noqa: S310  # nosemgrep  (fixed https host)
        out = json.loads(resp.read().decode())
    return {"number": out.get("number"), "url": out.get("html_url")}


def _allow(user_id: str) -> bool:
    now = time.time()
    with _lock:
        recent = [t for t in _sent.get(user_id, []) if now - t < 86400]
        if (recent and now - recent[-1] < 60) or len(recent) >= 10:
            _sent[user_id] = recent
            return False
        recent.append(now)
        _sent[user_id] = recent
        return True


@router.get("")
def status_(_: User = Depends(current_user)) -> dict:
    s = get_settings()
    return {"enabled": bool(s.issue_token and s.issue_repo), "repo": s.issue_repo}


@router.post("")
def report(body: BugReportIn, user: User = Depends(current_user)) -> dict:
    s = get_settings()
    if not (s.issue_token and s.issue_repo):
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, "Bug reports can't be sent from here yet.")
    if not _allow(user.id):
        raise HTTPException(status.HTTP_429_TOO_MANY_REQUESTS, "Please wait a minute before sending another report.")
    title = "[Bug] " + body.summary.replace("\n", " ")[:120]
    try:
        return send_issue(s.issue_repo, s.issue_token, defuse(title), issue_body(body, "web / desktop app"))
    except (urllib.error.URLError, TimeoutError, ValueError) as exc:
        raise HTTPException(status.HTTP_502_BAD_GATEWAY, "Couldn't reach GitHub. Please try again later.") from exc
