"""Departure lookup tests — assert the output matches proxy._shape_departures
field set and that RT entries dedupe scheduled entries for the same trip."""
import time
from datetime import datetime, timezone

from gtfs511 import lookup, rt_db, static_db
from tests.gtfs511_fixtures import make_static_gtfs_zip, make_tripupdates_pb


# The 19 fields proxy._shape_departures emits. Lookup output must match this set.
_TRANSITLAND_FIELDS = {
    "route_short_name", "headsign",
    "scheduled_departure_time", "scheduled_departure_utc", "scheduled_departure_minutes",
    "live_departure_time", "live_departure_utc", "live_departure_minutes",
    "time_source", "schedule_relationship",
    "agency_name",
    "feed_onestop_id", "feed_name",
    "attribution_text", "attribution_instructions", "use_without_attribution",
    "license_spdx", "license_url",
}

# Use a date deep enough in the future that the fixture's calendar covers it
# and the local time is unambiguous re: DST.
_NOW_UTC = int(datetime(2026, 5, 20, 16, 0, 0, tzinfo=timezone.utc).timestamp())  # 09:00 PDT


def _meta() -> dict:
    return {
        "feed_onestop_id": "f-9q9-fakebart",
        "feed_name": "Fake BART",
        "attribution_text": "Fake BART via 511",
        "attribution_instructions": "Test data",
        "use_without_attribution": False,
        "license_spdx": None,
        "license_url": "https://example.com/license",
    }


def _build_static(tmp_path, departure_time="10:00:00"):
    zip_bytes = make_static_gtfs_zip(
        service_dates=["20260519", "20260521"],
        departure_time=departure_time,
    )
    target = str(tmp_path / "static.sqlite")
    static_db.build_static_db(zip_bytes, target)
    return static_db.open_static_db(target)


def test_lookup_returns_scheduled_when_no_rt(tmp_path):
    sdb = _build_static(tmp_path)
    try:
        out = lookup.lookup_departures(
            static_db=sdb, rt_db=None,
            stop_id="CHILD1", feed_metadata=_meta(),
            next_seconds=7200, now_utc=_NOW_UTC,
        )
    finally:
        sdb.close()

    assert len(out) == 2  # T1 + T2 both stop at CHILD1 at 10:00
    for rec in out:
        assert set(rec.keys()) == _TRANSITLAND_FIELDS
        assert rec["time_source"] == "SCHEDULED"
        assert rec["scheduled_departure_utc"] is not None
        assert rec["live_departure_utc"] is None
        assert rec["feed_onestop_id"] == "f-9q9-fakebart"
        assert rec["feed_name"] == "Fake BART"
        assert rec["agency_name"] == "Test Agency"


def test_lookup_returns_live_when_rt_present(tmp_path):
    sdb = _build_static(tmp_path)
    # RT pushes a prediction for T1 at CHILD1 — should override the SCHEDULED row.
    predicted = _NOW_UTC + 1900  # ~32 min from "now"
    pb = make_tripupdates_pb(predicted_epoch=predicted, trip_id="T1",
                              stop_id="CHILD1", stop_seq=1, delay=120)
    rt_target = str(tmp_path / "rt.sqlite")
    rt_db.build_rt_db(pb, None, rt_target)
    rdb = rt_db.open_rt_db(rt_target)
    try:
        out = lookup.lookup_departures(
            static_db=sdb, rt_db=rdb,
            stop_id="CHILD1", feed_metadata=_meta(),
            next_seconds=7200, now_utc=_NOW_UTC,
        )
    finally:
        rdb.close()
        sdb.close()

    # Exactly one LIVE row (T1) + one SCHEDULED row (T2).
    live = [r for r in out if r["time_source"] == "LIVE"]
    sched = [r for r in out if r["time_source"] == "SCHEDULED"]
    assert len(live) == 1
    assert len(sched) == 1
    assert live[0]["route_short_name"] == "1"
    assert live[0]["headsign"] == "Toward Downtown"
    assert live[0]["live_departure_utc"] == "2026-05-20T16:31:40Z"
    assert sched[0]["route_short_name"] == "2"


def test_lookup_resolves_parent_station_to_children(tmp_path):
    """Querying by parent_station should pull the child's stop_times."""
    sdb = _build_static(tmp_path)
    try:
        out = lookup.lookup_departures(
            static_db=sdb, rt_db=None,
            stop_id="PARENT", feed_metadata=_meta(),
            next_seconds=7200, now_utc=_NOW_UTC,
        )
    finally:
        sdb.close()
    assert len(out) == 2  # Both trips at CHILD1, reachable via PARENT.


def test_lookup_respects_next_seconds_window(tmp_path):
    sdb = _build_static(tmp_path)
    try:
        # Window of 1 minute starting at "now" — 10:00 PDT is 60 minutes away.
        out = lookup.lookup_departures(
            static_db=sdb, rt_db=None,
            stop_id="CHILD1", feed_metadata=_meta(),
            next_seconds=60, now_utc=_NOW_UTC,
        )
    finally:
        sdb.close()
    assert out == []


def test_lookup_sorts_by_effective_departure(tmp_path):
    sdb = _build_static(tmp_path)
    # RT for T1 places it AFTER T2 in time (40 minutes vs T2's scheduled 60 minutes).
    predicted_late_t1 = _NOW_UTC + 4200  # 70 minutes
    pb = make_tripupdates_pb(predicted_epoch=predicted_late_t1, trip_id="T1",
                              stop_id="CHILD1", stop_seq=1, delay=600)
    rt_target = str(tmp_path / "rt.sqlite")
    rt_db.build_rt_db(pb, None, rt_target)
    rdb = rt_db.open_rt_db(rt_target)
    try:
        out = lookup.lookup_departures(
            static_db=sdb, rt_db=rdb,
            stop_id="CHILD1", feed_metadata=_meta(),
            next_seconds=7200, now_utc=_NOW_UTC,
        )
    finally:
        rdb.close()
        sdb.close()
    # T2 (scheduled 60min) should come first, then T1 (LIVE 70min).
    assert [r["route_short_name"] for r in out] == ["2", "1"]
