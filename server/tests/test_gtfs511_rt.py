"""GTFS-Realtime parsing + indexing tests."""
import glob
import os
import sqlite3
import time
from unittest.mock import patch

from gtfs511 import rt_db
from tests.gtfs511_fixtures import make_tripupdates_pb, make_servicealerts_pb


def test_build_rt_db_inserts_trip_update_row(tmp_path):
    target = str(tmp_path / "rt.sqlite")
    predicted = int(time.time()) + 300
    pb = make_tripupdates_pb(predicted_epoch=predicted, delay=60)
    metrics = rt_db.build_rt_db(pb, alerts_bytes=None, target_path=target)

    assert metrics.rt_rows == 1
    assert metrics.alert_rows == 0
    assert metrics.entities_skipped == 0
    assert os.path.isfile(target)

    db = rt_db.open_rt_db(target)
    try:
        rows = db.execute(
            "SELECT trip_id, stop_id, predicted_utc, scheduled_utc, delay_seconds "
            "FROM rt_trip_stop_times"
        ).fetchall()
        assert len(rows) == 1
        r = rows[0]
        assert r["trip_id"] == "T1"
        assert r["stop_id"] == "CHILD1"
        assert r["predicted_utc"] == predicted
        # scheduled = predicted - delay
        assert r["scheduled_utc"] == predicted - 60
        assert r["delay_seconds"] == 60
    finally:
        db.close()


def test_build_rt_db_with_alerts(tmp_path):
    target = str(tmp_path / "rt.sqlite")
    pb = make_tripupdates_pb(predicted_epoch=int(time.time()) + 60)
    alerts_pb = make_servicealerts_pb(header="Power outage", description="No service east of MacArthur.")
    metrics = rt_db.build_rt_db(pb, alerts_bytes=alerts_pb, target_path=target)
    assert metrics.alert_rows == 1

    db = rt_db.open_rt_db(target)
    try:
        row = db.execute(
            "SELECT header, description, cause, effect FROM rt_alerts"
        ).fetchone()
        assert row["header"] == "Power outage"
        assert row["description"] == "No service east of MacArthur."
        assert row["cause"] == "MAINTENANCE"
        assert row["effect"] == "MODIFIED_SERVICE"
    finally:
        db.close()


def test_rebuild_replaces_previous_snapshot(tmp_path):
    target = str(tmp_path / "rt.sqlite")
    pb1 = make_tripupdates_pb(predicted_epoch=int(time.time()) + 60, trip_id="T1")
    rt_db.build_rt_db(pb1, None, target)
    pb2 = make_tripupdates_pb(predicted_epoch=int(time.time()) + 120, trip_id="T_NEW")
    rt_db.build_rt_db(pb2, None, target)

    db = rt_db.open_rt_db(target)
    try:
        trip_ids = [r[0] for r in db.execute("SELECT trip_id FROM rt_trip_stop_times")]
        assert trip_ids == ["T_NEW"]
    finally:
        db.close()


def test_rt_refreshed_at_present_after_build(tmp_path):
    target = str(tmp_path / "rt.sqlite")
    pb = make_tripupdates_pb(predicted_epoch=int(time.time()) + 60)
    rt_db.build_rt_db(pb, None, target)
    ts = rt_db.rt_refreshed_at(target)
    assert ts is not None
    assert abs(ts - int(time.time())) < 5


def test_rt_refreshed_at_missing_file(tmp_path):
    assert rt_db.rt_refreshed_at(str(tmp_path / "absent.sqlite")) is None


def test_build_rt_db_atomic_swap(tmp_path):
    """build_rt_db must write to a temp file then os.replace — no partial writes visible."""
    target = str(tmp_path / "rt.sqlite")
    pb = make_tripupdates_pb(predicted_epoch=int(time.time()) + 300)
    replaced = []

    original_replace = os.replace
    def recording_replace(src, dst):
        replaced.append((src, dst))
        original_replace(src, dst)

    with patch("gtfs511.rt_db.os.replace", side_effect=recording_replace):
        rt_db.build_rt_db(pb, None, target)

    assert len(replaced) == 1, "os.replace must be called exactly once"
    assert replaced[0][1] == target
    assert not glob.glob(str(tmp_path / ".gtfs_rt.*")), "temp file must be cleaned up after replace"


def test_build_rt_db_temp_cleanup_on_exception(tmp_path):
    """On failure the temp file is removed and the existing target is untouched."""
    target = str(tmp_path / "rt.sqlite")
    pb1 = make_tripupdates_pb(predicted_epoch=int(time.time()) + 60, trip_id="T1")
    rt_db.build_rt_db(pb1, None, target)
    original_mtime = os.path.getmtime(target)

    pb2 = make_tripupdates_pb(predicted_epoch=int(time.time()) + 120, trip_id="T2")
    # Inject failure at os.replace (after the DB is fully written to the temp file).
    with patch("gtfs511.rt_db.os.replace", side_effect=OSError("injected")):
        try:
            rt_db.build_rt_db(pb2, None, target)
        except OSError:
            pass

    assert not glob.glob(str(tmp_path / ".gtfs_rt.*")), "temp file must be cleaned up on failure"
    assert os.path.getmtime(target) == original_mtime, "existing target must be untouched on failure"


def test_no_wal_sidecar_files(tmp_path):
    """build_rt_db must not leave -shm or -wal files next to the target."""
    target = str(tmp_path / "rt.sqlite")
    pb = make_tripupdates_pb(predicted_epoch=int(time.time()) + 300)
    rt_db.build_rt_db(pb, None, target)

    assert not os.path.exists(target + "-shm"), "-shm sidecar must not exist"
    assert not os.path.exists(target + "-wal"), "-wal sidecar must not exist"


def test_open_rt_db_busy_timeout(tmp_path):
    """open_rt_db must set busy_timeout = 5000 ms."""
    target = str(tmp_path / "rt.sqlite")
    pb = make_tripupdates_pb(predicted_epoch=int(time.time()) + 300)
    rt_db.build_rt_db(pb, None, target)

    db = rt_db.open_rt_db(target)
    try:
        row = db.execute("PRAGMA busy_timeout").fetchone()
        assert row[0] == 5000
    finally:
        db.close()
