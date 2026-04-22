import os
import tempfile
import time
from datetime import datetime, timezone, timedelta

os.environ.setdefault("TRANSITLAND_API_KEY", "dummy")
os.environ.setdefault("APP_API_KEYS", "key-one")

import pytest


def _make_log(entries):
    """Write Apache combined log lines to a temp file and return the path."""
    f = tempfile.NamedTemporaryFile(mode="w", suffix=".log", delete=False)
    for ip, ts in entries:
        ts_str = ts.strftime("%d/%b/%Y:%H:%M:%S %z")
        f.write(f'{ip} - - [{ts_str}] "GET /api HTTP/1.1" 200 100 "-" "-"\n')
    f.flush()
    f.close()
    return f.name


def _now():
    return datetime.now(tz=timezone.utc)


def test_under_limit_allowed(tmp_path):
    import importlib
    import rate_limit as rl

    now = _now()
    entries = [("1.2.3.4", now - timedelta(minutes=i)) for i in range(10)]
    log_path = _make_log(entries)

    rl._log_path = log_path
    rl._limit = 500
    rl._cache.clear()

    allowed, retry = rl.check_rate_limit("1.2.3.4")
    assert allowed is True
    assert retry == 0

    os.unlink(log_path)


def test_at_limit_blocked(tmp_path):
    import rate_limit as rl

    now = _now()
    entries = [("1.2.3.4", now - timedelta(seconds=i * 6)) for i in range(500)]
    log_path = _make_log(entries)

    rl._log_path = log_path
    rl._limit = 500
    rl._cache.clear()

    allowed, retry = rl.check_rate_limit("1.2.3.4")
    assert allowed is False
    assert retry > 0

    os.unlink(log_path)


def test_other_ip_not_counted():
    import rate_limit as rl

    now = _now()
    entries = [("9.9.9.9", now - timedelta(minutes=i)) for i in range(500)]
    log_path = _make_log(entries)

    rl._log_path = log_path
    rl._limit = 500
    rl._cache.clear()

    allowed, retry = rl.check_rate_limit("1.2.3.4")
    assert allowed is True

    os.unlink(log_path)


def test_old_entries_not_counted():
    import rate_limit as rl

    now = _now()
    old = [("1.2.3.4", now - timedelta(hours=2) - timedelta(minutes=i)) for i in range(500)]
    recent = [("1.2.3.4", now - timedelta(minutes=1))]
    log_path = _make_log(old + recent)

    rl._log_path = log_path
    rl._limit = 500
    rl._cache.clear()

    allowed, retry = rl.check_rate_limit("1.2.3.4")
    assert allowed is True

    os.unlink(log_path)


def test_missing_log_allows_request(tmp_path):
    import rate_limit as rl

    rl._log_path = str(tmp_path / "nonexistent.log")
    rl._cache.clear()
    rl._log_missing_warned = False

    allowed, retry = rl.check_rate_limit("1.2.3.4")
    assert allowed is True


def test_retry_after_is_positive():
    import rate_limit as rl

    now = _now()
    entries = [("1.2.3.4", now - timedelta(seconds=i * 6)) for i in range(500)]
    log_path = _make_log(entries)

    rl._log_path = log_path
    rl._limit = 500
    rl._cache.clear()

    allowed, retry = rl.check_rate_limit("1.2.3.4")
    assert not allowed
    assert 0 < retry <= 3600

    os.unlink(log_path)
