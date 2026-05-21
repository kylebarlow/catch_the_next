"""GTFS-Realtime parsing + indexing tests."""
import os
import time

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
