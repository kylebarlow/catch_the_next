"""Static GTFS ingestion tests."""
import os
import sqlite3
from datetime import datetime, timezone

from gtfs511 import static_db
from tests.gtfs511_fixtures import make_static_gtfs_zip


def _today_yyyymmdd() -> str:
    return datetime.now(tz=timezone.utc).strftime("%Y%m%d")


def test_build_static_db_populates_all_tables(tmp_path):
    zip_bytes = make_static_gtfs_zip(service_dates=[_today_yyyymmdd()])
    target = str(tmp_path / "static.sqlite")
    metrics = static_db.build_static_db(zip_bytes, target)

    assert metrics.final_db_bytes > 0
    assert os.path.isfile(target)

    # Row counts match the fixture.
    assert metrics.rows_per_table["agency"] == 1
    assert metrics.rows_per_table["routes"] == 2
    assert metrics.rows_per_table["stops"] == 3
    assert metrics.rows_per_table["trips"] == 2
    assert metrics.rows_per_table["stop_times"] == 4
    assert metrics.rows_per_table["calendar"] == 1

    db = sqlite3.connect(target)
    try:
        # Indexes exist.
        idx = {r[0] for r in db.execute(
            "SELECT name FROM sqlite_master WHERE type='index'"
        )}
        assert "idx_stop_times_stop" in idx
        assert "idx_stop_times_trip" in idx

        # feed_meta carries refresh metadata.
        row = db.execute(
            "SELECT value FROM feed_meta WHERE key = 'refreshed_at'"
        ).fetchone()
        assert row is not None
        assert int(row[0]) > 0

        # Spot-check joinability for the child→stop_times pattern.
        rows = db.execute(
            "SELECT stop_id FROM stops WHERE parent_station = 'PARENT'"
        ).fetchall()
        assert rows == [("CHILD1",)]
    finally:
        db.close()


def test_atomic_overwrite_keeps_previous_db_until_done(tmp_path):
    target = str(tmp_path / "static.sqlite")
    zip1 = make_static_gtfs_zip(service_dates=[_today_yyyymmdd()])
    static_db.build_static_db(zip1, target)
    first_size = os.path.getsize(target)

    # Rebuild with identical content; file must still exist and have content.
    static_db.build_static_db(zip1, target)
    assert os.path.isfile(target)
    assert os.path.getsize(target) > 0
    # File size should be roughly identical (same fixture, same VACUUM).
    assert abs(os.path.getsize(target) - first_size) < 1024


def test_static_refreshed_at_returns_recent_timestamp(tmp_path):
    target = str(tmp_path / "static.sqlite")
    static_db.build_static_db(
        make_static_gtfs_zip(service_dates=[_today_yyyymmdd()]),
        target,
    )
    ts = static_db.static_refreshed_at(target)
    assert ts is not None
    assert abs(ts - int(datetime.now(tz=timezone.utc).timestamp())) < 5


def test_static_refreshed_at_returns_none_when_missing(tmp_path):
    assert static_db.static_refreshed_at(str(tmp_path / "absent.sqlite")) is None


def test_missing_required_table_raises(tmp_path):
    # A zip without agency.txt should fail loudly.
    import io
    import zipfile
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as zf:
        zf.writestr("routes.txt", "route_id\n")
    target = str(tmp_path / "broken.sqlite")
    import pytest
    with pytest.raises(ValueError, match="agency.txt"):
        static_db.build_static_db(buf.getvalue(), target)
    # Failed builds must not leave the target in place.
    assert not os.path.isfile(target)
