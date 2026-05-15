import json
import os
import random
import sqlite3
import time

from config import load_config

_cfg = load_config()
_db_path = _cfg["CACHE_DB_PATH"]

_SWEEP_ODDS = 20  # run expired-row sweep on 1-in-N writes

_DDL = """
CREATE TABLE IF NOT EXISTS cache (
    key        TEXT    PRIMARY KEY,
    value      BLOB    NOT NULL,
    expires_at REAL    NOT NULL
);
"""

# For :memory: databases every sqlite3.connect() call creates a separate,
# empty DB. Use the shared-cache URI so independent connections see the same data
# while each gets its own transaction (thread-safe).
_MEMORY_URI = "file::memory:?cache=shared&mode=memory"


def _connect() -> sqlite3.Connection:
    if _db_path == ":memory:":
        db = sqlite3.connect(_MEMORY_URI, uri=True, check_same_thread=False, timeout=5)
    else:
        db = sqlite3.connect(_db_path, timeout=5)
    db.execute("PRAGMA journal_mode=WAL")
    db.execute(_DDL)
    return db


def get(key: str):
    """Return the cached Python object for *key*, or None if absent/expired."""
    try:
        with _connect() as db:
            row = db.execute(
                "SELECT value, expires_at FROM cache WHERE key = ?", (key,)
            ).fetchone()
    except sqlite3.Error:
        return None
    if row is None:
        return None
    value_blob, expires_at = row
    if time.time() > expires_at:
        return None
    try:
        return json.loads(value_blob)
    except (ValueError, TypeError):
        return None


def set(key: str, value, ttl_seconds: float):
    """Store *value* (any JSON-serialisable object) under *key* for *ttl_seconds*."""
    blob = json.dumps(value)
    expires_at = time.time() + ttl_seconds
    try:
        with _connect() as db:
            db.execute(
                "INSERT OR REPLACE INTO cache (key, value, expires_at) VALUES (?, ?, ?)",
                (key, blob, expires_at),
            )
            if random.randint(0, _SWEEP_ODDS - 1) == 0:
                db.execute("DELETE FROM cache WHERE expires_at < ?", (time.time(),))
    except sqlite3.Error:
        pass  # cache miss is always safe to ignore


def delete(key: str):
    """Evict a single key (used to invalidate stale stop-id mappings)."""
    try:
        with _connect() as db:
            db.execute("DELETE FROM cache WHERE key = ?", (key,))
    except sqlite3.Error:
        pass
