"""Persistent hourly counters for upstream API calls.

Stored in the same SQLite DB as the response cache so no extra config is
needed. Counters are bucketed by hour so any time-window query is a simple
SUM over the relevant rows.
"""

import sqlite3
import time
from datetime import datetime, timezone

from config import load_config

_cfg = load_config()
_db_path = _cfg["CACHE_DB_PATH"]

_DDL = """
CREATE TABLE IF NOT EXISTS upstream_calls (
    counter     TEXT    NOT NULL,
    hour_bucket INTEGER NOT NULL,
    count       INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (counter, hour_bucket)
);
"""

_MEMORY_URI = "file::memory:?cache=shared&mode=memory"


def _connect() -> sqlite3.Connection:
    if _db_path == ":memory:":
        db = sqlite3.connect(_MEMORY_URI, uri=True, check_same_thread=False, timeout=5)
    else:
        db = sqlite3.connect(_db_path, timeout=5)
    db.execute("PRAGMA journal_mode=WAL")
    db.execute(_DDL)
    return db


def increment(name: str, count: int = 1) -> None:
    """Add *count* to the current hour bucket for *name*. Silently drops errors."""
    bucket = int(time.time()) // 3600 * 3600
    try:
        with _connect() as db:
            db.execute(
                """INSERT INTO upstream_calls (counter, hour_bucket, count)
                   VALUES (?, ?, ?)
                   ON CONFLICT (counter, hour_bucket)
                   DO UPDATE SET count = count + excluded.count""",
                (name, bucket, count),
            )
    except sqlite3.Error:
        pass


def query_since(name: str, since_epoch: float) -> int:
    """Sum of *name* counter for all hour buckets >= *since_epoch*."""
    since_bucket = int(since_epoch) // 3600 * 3600
    try:
        with _connect() as db:
            row = db.execute(
                "SELECT COALESCE(SUM(count), 0) FROM upstream_calls "
                "WHERE counter = ? AND hour_bucket >= ?",
                (name, since_bucket),
            ).fetchone()
        return int(row[0]) if row else 0
    except sqlite3.Error:
        return 0


def query_all(name: str) -> int:
    """Total sum of *name* across all stored buckets."""
    try:
        with _connect() as db:
            row = db.execute(
                "SELECT COALESCE(SUM(count), 0) FROM upstream_calls WHERE counter = ?",
                (name,),
            ).fetchone()
        return int(row[0]) if row else 0
    except sqlite3.Error:
        return 0


def query_daily(name: str, since_epoch: float) -> dict:
    """Return {YYYY-MM-DD: count} totals for *name* since *since_epoch*."""
    since_bucket = int(since_epoch) // 3600 * 3600
    try:
        with _connect() as db:
            rows = db.execute(
                "SELECT hour_bucket, count FROM upstream_calls "
                "WHERE counter = ? AND hour_bucket >= ?",
                (name, since_bucket),
            ).fetchall()
        result: dict[str, int] = {}
        for bucket, cnt in rows:
            day = datetime.fromtimestamp(bucket, tz=timezone.utc).strftime("%Y-%m-%d")
            result[day] = result.get(day, 0) + cnt
        return result
    except sqlite3.Error:
        return {}
