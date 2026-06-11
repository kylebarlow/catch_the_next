"""Departure lookup tests — assert the output matches proxy._shape_departures
field set and that RT entries dedupe scheduled entries for the same trip."""
import time
from datetime import datetime, timedelta, timezone
from zoneinfo import ZoneInfo

from gtfs511 import lookup, rt_db, static_db
from tests.gtfs511_fixtures import (
    make_servicealerts_pb,
    make_static_gtfs_zip,
    make_tripupdates_pb,
)

# Fields proxy._shape_alerts emits — lookup_alerts must match this set.
_ALERT_FIELDS = {
    "cause", "effect", "severity_level", "header_text", "description_text",
    "tts_header_text", "tts_description_text", "url", "active_period",
}


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

# Anchor "now" to 09:00 local (America/Los_Angeles) on today's date so the
# fixture's service window (built relative to date.today()) always covers it.
# Departures in the fixture are at 10:00 local, i.e. 60 minutes after "now".
_PACIFIC = ZoneInfo("America/Los_Angeles")
_NOW_LOCAL = datetime.now(tz=_PACIFIC).replace(hour=9, minute=0, second=0, microsecond=0)
_NOW_UTC = int(_NOW_LOCAL.timestamp())


def _service_dates():
    today = _NOW_LOCAL.date()
    return [(today + timedelta(days=d)).strftime("%Y%m%d") for d in (-1, 0, 1)]


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
        service_dates=_service_dates(),
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
    expected_utc = datetime.fromtimestamp(predicted, tz=timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    assert live[0]["live_departure_utc"] == expected_utc
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


# ─── alerts ─────────────────────────────────────────────────────────────────


def _build_rt_with_alert(tmp_path, **alert_kwargs):
    pb = make_tripupdates_pb(predicted_epoch=_NOW_UTC + 600, trip_id="T1")
    alerts_pb = make_servicealerts_pb(**alert_kwargs)
    rt_target = str(tmp_path / "rt.sqlite")
    rt_db.build_rt_db(pb, alerts_bytes=alerts_pb, target_path=rt_target)
    return rt_db.open_rt_db(rt_target)


def _alerts_for_stop(tmp_path, stop_id="CHILD1", **alert_kwargs):
    sdb = _build_static(tmp_path)
    rdb = _build_rt_with_alert(tmp_path, **alert_kwargs)
    try:
        return lookup.lookup_alerts(
            static_db=sdb, rt_db=rdb, stop_id=stop_id, now_utc=_NOW_UTC,
        )
    finally:
        rdb.close()
        sdb.close()


def test_alerts_none_when_no_rt_db(tmp_path):
    sdb = _build_static(tmp_path)
    try:
        assert lookup.lookup_alerts(
            static_db=sdb, rt_db=None, stop_id="CHILD1", now_utc=_NOW_UTC,
        ) == []
    finally:
        sdb.close()


def test_feed_wide_alert_included(tmp_path):
    # No informed entities → feed-wide, applies to every stop.
    out = _alerts_for_stop(tmp_path, header="System alert", informed_entities=None)
    assert len(out) == 1
    assert set(out[0].keys()) == _ALERT_FIELDS
    assert out[0]["header_text"] == "System alert"
    assert out[0]["cause"] == "MAINTENANCE"
    assert out[0]["effect"] == "MODIFIED_SERVICE"


def test_alert_matched_by_stop_id(tmp_path):
    out = _alerts_for_stop(tmp_path, informed_entities=[{"stop_id": "CHILD1"}])
    assert len(out) == 1


def test_alert_matched_by_parent_station_child(tmp_path):
    # Query the parent; alert names the child platform.
    out = _alerts_for_stop(tmp_path, stop_id="PARENT",
                           informed_entities=[{"stop_id": "CHILD1"}])
    assert len(out) == 1


def test_alert_matched_by_route_serving_stop(tmp_path):
    # R1 serves CHILD1 in the fixture.
    out = _alerts_for_stop(tmp_path, informed_entities=[{"route_id": "R1"}])
    assert len(out) == 1


def test_alert_matched_by_agency_serving_stop(tmp_path):
    out = _alerts_for_stop(tmp_path, informed_entities=[{"agency_id": "TA"}])
    assert len(out) == 1


def test_alert_not_matched_by_unrelated_stop(tmp_path):
    out = _alerts_for_stop(tmp_path, informed_entities=[{"stop_id": "OTHER"}])
    assert out == []


def test_alert_not_matched_by_unrelated_route(tmp_path):
    out = _alerts_for_stop(tmp_path, informed_entities=[{"route_id": "R999"}])
    assert out == []


def test_alert_anded_within_entity(tmp_path):
    # route R1 serves the stop but stop_id OTHER does not — ANDed, so no match.
    out = _alerts_for_stop(
        tmp_path, informed_entities=[{"route_id": "R1", "stop_id": "OTHER"}],
    )
    assert out == []


def test_inactive_alert_filtered_out(tmp_path):
    # active_period entirely in the past.
    out = _alerts_for_stop(
        tmp_path,
        informed_entities=[{"agency_id": "TA"}],
        active_period=(_NOW_UTC - 7200, _NOW_UTC - 3600),
    )
    assert out == []


def test_active_alert_within_period_included(tmp_path):
    out = _alerts_for_stop(
        tmp_path,
        informed_entities=[{"agency_id": "TA"}],
        active_period=(_NOW_UTC - 3600, _NOW_UTC + 3600),
    )
    assert len(out) == 1
    assert out[0]["active_period"] == [{"start": _NOW_UTC - 3600, "end": _NOW_UTC + 3600}]
