"""
database.py — opens the SQLite database and hands out "sessions".

A *session* is a short conversation with the database: the API opens one
per web request, reads/writes, then closes it.  SQLite was chosen because the
whole database is one file inside your Docker volume — trivial to back up by
copying it — and it needs no separate database server.

All database access goes through SQLAlchemy (an ORM), which always uses
parameterized queries, so user input can never be spliced into SQL text
(this blocks "SQL injection" attacks).
"""

from __future__ import annotations

from collections.abc import Iterator

from sqlalchemy import create_engine, event
from sqlalchemy.engine import Engine
from sqlalchemy.orm import DeclarativeBase, Session, sessionmaker

from .config import get_settings


class Base(DeclarativeBase):
    """Parent class of every table definition in models.py."""


_engine: Engine | None = None
_SessionLocal: sessionmaker[Session] | None = None


def _configure_sqlite(dbapi_connection, _record) -> None:
    """Runs for every new connection: turn on safety features of SQLite.

    * foreign_keys  — refuse rows that point at things that don't exist
    * journal_mode=WAL — lets reads continue while a write is in progress
    * busy_timeout — wait (instead of failing) if another write is running
    """
    cursor = dbapi_connection.cursor()
    cursor.execute("PRAGMA foreign_keys=ON")
    cursor.execute("PRAGMA journal_mode=WAL")
    cursor.execute("PRAGMA busy_timeout=5000")
    cursor.close()


def get_engine() -> Engine:
    """Create the database engine once (lazily) and reuse it."""
    global _engine, _SessionLocal
    if _engine is None:
        settings = get_settings()
        settings.data_dir.mkdir(parents=True, exist_ok=True)
        _engine = create_engine(
            f"sqlite:///{settings.db_path}",
            connect_args={"check_same_thread": False},
        )
        event.listen(_engine, "connect", _configure_sqlite)
        _SessionLocal = sessionmaker(bind=_engine, expire_on_commit=False)
    return _engine


def reset_engine() -> None:
    """Forget the current engine (used by the automated tests)."""
    global _engine, _SessionLocal
    if _engine is not None:
        _engine.dispose()
    _engine = None
    _SessionLocal = None


def init_db() -> None:
    """Create any missing tables.  Safe to run on every start."""
    from . import models  # noqa: F401  (import registers the tables)

    Base.metadata.create_all(get_engine())


def get_db() -> Iterator[Session]:
    """FastAPI dependency: one database session per request, always closed."""
    get_engine()
    assert _SessionLocal is not None
    db = _SessionLocal()
    try:
        yield db
    finally:
        db.close()


def session_scope() -> Session:
    """Open a session outside a web request (background jobs, seeding)."""
    get_engine()
    assert _SessionLocal is not None
    return _SessionLocal()
