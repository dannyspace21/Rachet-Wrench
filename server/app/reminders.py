"""
reminders.py — working out when maintenance is due, and push alerts.

A reminder says "every N miles and/or every M months".  Starting from when
it was last done, it is due at whichever limit is reached FIRST:

    due mileage = last done mileage + N
    due date    = last done date    + M months

Status:  ok  ->  due_soon (within 500 mi or 30 days)  ->  overdue.

Push alerts (optional): a background task checks every
RW_REMINDER_CHECK_MINUTES and, for users who switched on ntfy and chose a
topic, POSTs a short message to YOUR ntfy server (RW_NTFY_URL).
OUTBOUND CONNECTION #2 (documented in SECURITY.md) — only goes to the ntfy
server you configure.  No email is ever sent.
"""

from __future__ import annotations

import asyncio
import calendar
import logging
import urllib.error
import urllib.parse
import urllib.request
from datetime import date, timedelta

from sqlalchemy import select
from sqlalchemy.orm import Session

from .catalog import type_label
from .config import get_settings
from .database import session_scope
from .models import Reminder, UserSettings, Vehicle, utcnow

log = logging.getLogger("ratchetwrench.reminders")

SOON_MILES = 500
SOON_DAYS = 30
RENOTIFY_DAYS = 3


def add_months(d: date, months: int) -> date:
    """Calendar-correct 'date + N months' (Jan 31 + 1 month = Feb 28/29)."""
    month_index = d.month - 1 + months
    year = d.year + month_index // 12
    month = month_index % 12 + 1
    day = min(d.day, calendar.monthrange(year, month)[1])
    return date(year, month, day)


def reminder_status(rem: Reminder, current_mileage: int | None, today: date | None = None) -> dict:
    """Compute due mileage/date and a status for one reminder."""
    today = today or date.today()
    due_miles = None
    due_date = None
    if rem.interval_miles and rem.last_done_mileage is not None:
        due_miles = rem.last_done_mileage + rem.interval_miles
    if rem.interval_months and rem.last_done_date is not None:
        due_date = add_months(rem.last_done_date, rem.interval_months)

    status = "unknown" if (due_miles is None and due_date is None) else "ok"
    miles_left = (due_miles - current_mileage) if (due_miles is not None and current_mileage is not None) else None
    days_left = (due_date - today).days if due_date else None

    if (miles_left is not None and miles_left <= 0) or (days_left is not None and days_left <= 0):
        status = "overdue"
    elif (miles_left is not None and miles_left <= SOON_MILES) or (days_left is not None and days_left <= SOON_DAYS):
        status = "due_soon"
    return {
        "due_mileage": due_miles,
        "due_date": due_date.isoformat() if due_date else None,
        "miles_left": miles_left,
        "days_left": days_left,
        "status": status if rem.enabled else "disabled",
    }


def mark_done_from_entry(db: Session, owner_id: str, vehicle_id: str, type_key: str, when: date, mileage: int | None) -> None:
    """When a maintenance entry is logged, move matching reminders forward
    (only if this entry is newer than what the reminder knew)."""
    rems = db.scalars(
        select(Reminder).where(
            Reminder.owner_id == owner_id,
            Reminder.vehicle_id == vehicle_id,
            Reminder.type_key == type_key,
            Reminder.deleted_at.is_(None),
        )
    ).all()
    now = utcnow()
    for rem in rems:
        if rem.last_done_date is None or when >= rem.last_done_date:
            rem.last_done_date = when
            if mileage is not None:
                rem.last_done_mileage = mileage
            rem.last_notified_status = ""
            rem.updated_at = now
            rem.server_modified_at = now


# ---------------------------------------------------------------------------
# ntfy push notifications
# ---------------------------------------------------------------------------
def send_ntfy(topic: str, title: str, message: str, priority: str = "default", timeout: float = 10.0) -> bool:
    """POST one notification to the configured ntfy server.  Returns success."""
    settings = get_settings()
    if not settings.ntfy_url or not topic:
        return False
    url = f"{settings.ntfy_url}/{urllib.parse.quote(topic, safe='')}"
    if not url.lower().startswith(("https://", "http://")):
        return False
    headers = {
        # Header values must be plain ASCII; strip anything else.
        "Title": title.encode("ascii", "ignore").decode()[:120],
        "Priority": priority,
        "Tags": "wrench",
    }
    if settings.ntfy_token:
        headers["Authorization"] = f"Bearer {settings.ntfy_token}"
    req = urllib.request.Request(url, data=message.encode("utf-8")[:4000], headers=headers, method="POST")
    try:
        # Reviewed: the URL comes from the server owner's .env and is checked
        # above to start with http:// or https:// (no file:// etc.).
        with urllib.request.urlopen(req, timeout=timeout) as resp:  # noqa: S310  # nosemgrep
            return 200 <= resp.status < 300
    except (urllib.error.URLError, TimeoutError, OSError) as exc:
        log.warning("ntfy notification failed: %s", type(exc).__name__)
        return False


def _vehicle_name(v: Vehicle) -> str:
    return v.nickname or " ".join(str(x) for x in (v.year, v.make, v.model) if x)


def check_and_notify(db: Session, today: date | None = None) -> int:
    """Send ntfy alerts for reminders that became due-soon/overdue.
    Returns how many notifications were sent."""
    sent = 0
    now = utcnow()
    users = db.scalars(select(UserSettings).where(UserSettings.ntfy_enabled.is_(True))).all()
    for us in users:
        if not us.ntfy_topic:
            continue
        rows = db.execute(
            select(Reminder, Vehicle)
            .join(Vehicle, Vehicle.id == Reminder.vehicle_id)
            .where(
                Reminder.owner_id == us.user_id,
                Reminder.deleted_at.is_(None),
                Reminder.enabled.is_(True),
                Vehicle.deleted_at.is_(None),
            )
        ).all()
        for rem, veh in rows:
            st = reminder_status(rem, veh.current_mileage, today)
            if st["status"] not in ("due_soon", "overdue"):
                continue
            recently = rem.last_notified_at and (now - rem.last_notified_at) < timedelta(days=RENOTIFY_DAYS)
            if rem.last_notified_status == st["status"] and recently:
                continue
            what = rem.title or type_label(rem.type_key)
            when = []
            if st["miles_left"] is not None:
                when.append(f"{abs(st['miles_left']):,} mi {'overdue' if st['miles_left'] <= 0 else 'left'}")
            if st["days_left"] is not None:
                when.append(f"{abs(st['days_left'])} days {'overdue' if st['days_left'] <= 0 else 'left'}")
            label = "Overdue" if st["status"] == "overdue" else "Due soon"
            if send_ntfy(us.ntfy_topic, f"{label}: {what}", f"{_vehicle_name(veh)} — {', '.join(when)}",
                         "high" if st["status"] == "overdue" else "default"):
                rem.last_notified_status = st["status"]
                rem.last_notified_at = now
                sent += 1
    db.commit()
    return sent


def _check_once() -> None:
    """One reminder check with its own database session (runs in a thread)."""
    with session_scope() as db:
        check_and_notify(db)


async def reminder_loop() -> None:
    """Background task started with the server (only if ntfy is configured)."""
    settings = get_settings()
    while True:
        try:
            await asyncio.to_thread(_check_once)
        except Exception:  # never let the loop die
            log.exception("reminder check failed")
        await asyncio.sleep(settings.reminder_check_minutes * 60)
