import os
os.environ.setdefault("CACHE_DB_PATH", ":memory:")

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
