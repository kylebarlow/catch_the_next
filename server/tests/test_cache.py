import os
os.environ.setdefault("TRANSITLAND_API_KEY", "test-key")
os.environ.setdefault("APP_API_KEYS", "test-key")
os.environ.setdefault("CACHE_DB_PATH", ":memory:")

import time
import pytest
import cache


def test_get_missing_key_returns_none():
    assert cache.get("no-such-key") is None


def test_set_and_get_roundtrip():
    cache.set("k1", {"hello": "world"}, ttl_seconds=60)
    assert cache.get("k1") == {"hello": "world"}


def test_set_and_get_various_types():
    cache.set("list", [1, 2, 3], ttl_seconds=60)
    assert cache.get("list") == [1, 2, 3]

    cache.set("int", 42, ttl_seconds=60)
    assert cache.get("int") == 42

    cache.set("nested", {"a": {"b": [True, None]}}, ttl_seconds=60)
    assert cache.get("nested") == {"a": {"b": [True, None]}}


def test_expired_entry_returns_none(monkeypatch):
    cache.set("exp", "value", ttl_seconds=1)
    # Fake time so the entry looks expired without actually sleeping.
    # Capture the real time.time before patching to avoid infinite recursion.
    _real_time = time.time
    monkeypatch.setattr(cache.time, "time", lambda: _real_time() + 100)
    assert cache.get("exp") is None


def test_delete_removes_entry():
    cache.set("to-delete", "gone", ttl_seconds=60)
    assert cache.get("to-delete") == "gone"
    cache.delete("to-delete")
    assert cache.get("to-delete") is None


def test_delete_nonexistent_key_is_silent():
    cache.delete("this-key-was-never-set")


def test_overwrite_existing_key():
    cache.set("ow", "first", ttl_seconds=60)
    cache.set("ow", "second", ttl_seconds=60)
    assert cache.get("ow") == "second"


def test_set_zero_ttl_is_immediately_expired():
    cache.set("zero-ttl", "instant", ttl_seconds=0)
    # Should be treated as expired right away (expires_at <= time.time()).
    assert cache.get("zero-ttl") is None


def test_concurrent_processes_share_db(tmp_path):
    """Two separate sqlite connections (simulating separate CGI processes) see
    each other's data — the whole point of the SQLite cache."""
    import importlib
    db_file = str(tmp_path / "shared.sqlite")

    os.environ["CACHE_DB_PATH"] = db_file
    importlib.reload(cache)  # picks up new CACHE_DB_PATH

    cache.set("shared", {"x": 1}, ttl_seconds=60)

    # Second "process": open a fresh connection and read back.
    import sqlite3
    import json as _json
    conn = sqlite3.connect(db_file)
    row = conn.execute("SELECT value FROM cache WHERE key = 'shared'").fetchone()
    conn.close()
    assert row is not None
    assert _json.loads(row[0]) == {"x": 1}

    # Restore :memory: for the rest of the test session.
    os.environ["CACHE_DB_PATH"] = ":memory:"
    importlib.reload(cache)
