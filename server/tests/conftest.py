import os
import tempfile

os.environ.setdefault("CACHE_DB_PATH", ":memory:")
# Tests that touch gtfs511 must isolate the DB directory; we set a default so
# importing the package never escapes into ~/protected. Per-test fixtures
# below redirect to tmp_path for true isolation.
os.environ.setdefault(
    "GTFS_511_DB_DIR",
    os.path.join(tempfile.gettempdir(), "gtfs511_default"),
)
os.environ.setdefault("FIVE_ELEVEN_API_KEY", "test-511-key")

import pytest


@pytest.fixture(autouse=True)
def clear_cache_between_tests():
    """Wipe the in-memory SQLite cache before each test to prevent cross-test pollution."""
    import cache
    if cache._db_path == ":memory:":
        try:
            with cache._connect() as db:
                db.execute("DELETE FROM cache")
        except Exception:
            pass
    yield


# NOTE: tests that call proxy.get_stops at Bay Area coords WITHOUT the
# gtfs511_dir fixture rely on this tmp default having no static DB — so
# gtfs511.nearby_stops returns None and the call falls through to the
# Transitland path. If a static DB ever lands here, those tests would switch to
# the local path and break.
@pytest.fixture
def gtfs511_dir(tmp_path, monkeypatch):
    """Redirect gtfs511's on-disk paths to a per-test tmp directory."""
    d = tmp_path / "gtfs511"
    d.mkdir()
    monkeypatch.setenv("GTFS_511_DB_DIR", str(d))
    # The api module captured the dir at import; rebind its constants.
    from gtfs511 import api as _api
    monkeypatch.setattr(_api, "_DB_DIR", str(d))
    monkeypatch.setattr(_api, "STATIC_DB_PATH", str(d / "gtfs_511_static.sqlite"))
    monkeypatch.setattr(_api, "RT_DB_PATH", str(d / "gtfs_511_rt.sqlite"))
    monkeypatch.setattr(_api, "_LOCK_DB_PATH", str(d / "refresh.lock.sqlite"))
    monkeypatch.setattr(_api, "_ALERTS_CACHE_PATH", str(d / "alerts.pb"))
    return d
