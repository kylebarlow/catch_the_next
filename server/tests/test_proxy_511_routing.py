"""Routing tests for proxy.get_departures_by_onestop_ids — verify Bay Area
stops are served from the gtfs511 path and non-Bay-Area stops continue to
hit Transitland."""
import os
os.environ.setdefault("TRANSITLAND_API_KEY", "test-key")
os.environ.setdefault("APP_API_KEYS", "test-key")

import sys
from types import SimpleNamespace
from unittest.mock import patch, MagicMock

import proxy


def _fresh_outcome(rt_age=5, static_age=3600):
    return SimpleNamespace(
        rt_age_seconds=rt_age, static_age_seconds=static_age,
        rt_error=None, static_error=None,
        refreshed_rt=True, refreshed_static=False,
    )


_STOPS_BART = {"stops": [{
    "id": 9001,
    "stop_id": "EMBR",
    "onestop_id": "s-bart-embarcadero",
    "geometry": {"coordinates": [-122.4, 37.79]},
    "feed_version": {"feed": {"onestop_id": "f-9q9-bart", "name": "BART"}},
}]}

_STOPS_NYC = {"stops": [{
    "id": 5002,
    "stop_id": "GRAND",
    "onestop_id": "s-nyc-grandcentral",
    "geometry": {"coordinates": [-73.97, 40.75]},
    "feed_version": {"feed": {"onestop_id": "f-dr5r-mta", "name": "MTA NYCT"}},
}]}

_FAKE_TL_DEPARTURES = {"stops": [{"departures": [], "children": []}]}


def _make_http_mock(stops_response):
    def side_effect(url, params=None, headers=None, timeout=None, allow_404=False):
        if "/stops?" in (url + "?"):
            return stops_response
        if "/stops/" in url and "/departures" in url:
            return _FAKE_TL_DEPARTURES
        raise AssertionError(f"Unexpected upstream call: {url}")
    return side_effect


def test_bay_area_stop_routes_to_gtfs511_not_transitland():
    """A BART stop should NOT trigger a Transitland /departures call."""
    fake_gtfs = MagicMock()
    fake_gtfs.is_bay_area_feed.return_value = True
    fake_gtfs.enabled.return_value = True
    fake_gtfs.refresh_if_stale.return_value = _fresh_outcome()
    fake_gtfs.lookup_departures.return_value = {
        ("f-9q9-bart", "EMBR"): {
            "departures": [{"route_short_name": "SFO", "headsign": "Millbrae"}],
            "alerts": [],
        }
    }

    with patch.object(proxy, "gtfs511", fake_gtfs), \
         patch.object(proxy, "_GTFS511_IMPORTED", True), \
         patch.object(proxy, "_http_get_json", side_effect=_make_http_mock(_STOPS_BART)) as mock_http:
        result = proxy.get_departures_by_onestop_ids(["s-bart-embarcadero"])

    # gtfs511 was called once.
    fake_gtfs.refresh_if_stale.assert_called_once()
    fake_gtfs.lookup_departures.assert_called_once()

    # Transitland /departures was NOT called — only /stops for resolution.
    departures_calls = [c for c in mock_http.call_args_list if "/departures" in c.args[0]]
    assert departures_calls == []

    # Output carries the 511 departures.
    assert result["stops"][0]["onestop_id"] == "s-bart-embarcadero"
    assert result["stops"][0]["departures"][0]["headsign"] == "Millbrae"


def test_non_bay_area_stop_falls_back_to_transitland():
    fake_gtfs = MagicMock()
    fake_gtfs.is_bay_area_feed.return_value = False
    fake_gtfs.enabled.return_value = True

    with patch.object(proxy, "gtfs511", fake_gtfs), \
         patch.object(proxy, "_GTFS511_IMPORTED", True), \
         patch.object(proxy, "_http_get_json", side_effect=_make_http_mock(_STOPS_NYC)) as mock_http:
        result = proxy.get_departures_by_onestop_ids(["s-nyc-grandcentral"])

    fake_gtfs.refresh_if_stale.assert_not_called()
    fake_gtfs.lookup_departures.assert_not_called()
    departures_calls = [c for c in mock_http.call_args_list if "/departures" in c.args[0]]
    assert len(departures_calls) == 1
    assert result["stops"][0]["onestop_id"] == "s-nyc-grandcentral"


def test_gtfs511_failure_falls_back_to_transitland(capsys):
    fake_gtfs = MagicMock()
    fake_gtfs.is_bay_area_feed.return_value = True
    fake_gtfs.enabled.return_value = True
    fake_gtfs.refresh_if_stale.side_effect = RuntimeError("simulated 511 outage")
    fake_gtfs.data_ages.return_value = (None, None)  # safety net → TL fallback

    with patch.object(proxy, "gtfs511", fake_gtfs), \
         patch.object(proxy, "_GTFS511_IMPORTED", True), \
         patch.object(proxy, "_http_get_json", side_effect=_make_http_mock(_STOPS_BART)) as mock_http:
        result = proxy.get_departures_by_onestop_ids(["s-bart-embarcadero"])

    departures_calls = [c for c in mock_http.call_args_list if "/departures" in c.args[0]]
    assert len(departures_calls) == 1  # Transitland was hit as fallback.
    assert result["stops"][0]["onestop_id"] == "s-bart-embarcadero"
    err = capsys.readouterr().err
    assert "refresh_if_stale raised" in err


# ── staleness policy + batch refresh ────────────────────────────────────────

_STOPS_BART2 = {"stops": [{
    "id": 9002, "stop_id": "MONT", "onestop_id": "s-bart-montgomery",
    "geometry": {"coordinates": [-122.4, 37.79]},
    "feed_version": {"feed": {"onestop_id": "f-9q9-bart", "name": "BART"}},
}]}


def _bay_gtfs(outcome=None):
    g = MagicMock()
    g.is_bay_area_feed.return_value = True
    g.enabled.return_value = True
    g.refresh_if_stale.return_value = outcome if outcome is not None else _fresh_outcome()
    g.lookup_departures.side_effect = lambda keys, ns: {
        keys[0]: {"departures": [{"headsign": "X"}], "alerts": []}
    }
    return g


def _two_stop_mock(url, params=None, headers=None, timeout=None, allow_404=False):
    if "/stops?" in (url + "?"):
        oid = (params or {}).get("onestop_id")
        return {"s-bart-embarcadero": _STOPS_BART, "s-bart-montgomery": _STOPS_BART2}[oid]
    if "/stops/" in url and "/departures" in url:
        return _FAKE_TL_DEPARTURES
    raise AssertionError(f"Unexpected upstream call: {url}")


def test_refresh_called_once_for_batch():
    g = _bay_gtfs()
    with patch.object(proxy, "gtfs511", g), patch.object(proxy, "_GTFS511_IMPORTED", True), \
         patch.object(proxy, "_http_get_json", side_effect=_two_stop_mock) as mock_http:
        proxy.get_departures_by_onestop_ids(["s-bart-embarcadero", "s-bart-montgomery"])
    g.refresh_if_stale.assert_called_once()
    assert g.lookup_departures.call_count == 2
    assert [c for c in mock_http.call_args_list if "/departures" in c.args[0]] == []


def test_rt_error_but_within_tolerance_served_locally():
    g = _bay_gtfs(_fresh_outcome(rt_age=120, static_age=3600))
    g.refresh_if_stale.return_value.rt_error = "timeout"
    with patch.object(proxy, "gtfs511", g), patch.object(proxy, "_GTFS511_IMPORTED", True), \
         patch.object(proxy, "_http_get_json", side_effect=_make_http_mock(_STOPS_BART)) as mock_http:
        proxy.get_departures_by_onestop_ids(["s-bart-embarcadero"])
    g.lookup_departures.assert_called_once()
    assert [c for c in mock_http.call_args_list if "/departures" in c.args[0]] == []


def test_rt_stale_falls_back_to_tl():
    import cache as _cache
    for outcome in (_fresh_outcome(rt_age=600), _fresh_outcome(rt_age=None),
                    _fresh_outcome(static_age=None)):
        with _cache._connect() as _db:
            _db.execute("DELETE FROM cache")
        g = _bay_gtfs(outcome)
        with patch.object(proxy, "gtfs511", g), patch.object(proxy, "_GTFS511_IMPORTED", True), \
             patch.object(proxy, "_http_get_json", side_effect=_make_http_mock(_STOPS_BART)) as mock_http:
            proxy.get_departures_by_onestop_ids(["s-bart-embarcadero"])
        g.lookup_departures.assert_not_called()
        assert len([c for c in mock_http.call_args_list if "/departures" in c.args[0]]) == 1


def test_local_lookup_exception_falls_back_to_tl():
    g = _bay_gtfs()
    g.lookup_departures.side_effect = RuntimeError("db gone")
    with patch.object(proxy, "gtfs511", g), patch.object(proxy, "_GTFS511_IMPORTED", True), \
         patch.object(proxy, "_http_get_json", side_effect=_make_http_mock(_STOPS_BART)) as mock_http:
        proxy.get_departures_by_onestop_ids(["s-bart-embarcadero"])
    assert len([c for c in mock_http.call_args_list if "/departures" in c.args[0]]) == 1


# ── synthetic ids ────────────────────────────────────────────────────────────

def _synthetic_gtfs(static_present=True):
    g = MagicMock()
    g.is_bay_area_feed.side_effect = lambda f: f == "f-sf~bay~area~rg"
    g.enabled.return_value = True
    g.refresh_if_stale.return_value = _fresh_outcome(
        static_age=3600 if static_present else None)
    g.lookup_departures.side_effect = lambda keys, ns: {
        keys[0]: {"departures": [{"headsign": "Y"}], "alerts": []}
    }
    return g


def test_synthetic_id_served_locally_no_upstream():
    g = _synthetic_gtfs()
    with patch.object(proxy, "gtfs511", g), patch.object(proxy, "_GTFS511_IMPORTED", True), \
         patch.object(proxy, "_http_get_json", side_effect=AssertionError("no upstream")) as mock_http:
        result = proxy.get_departures_by_onestop_ids(["511:f-sf~bay~area~rg:CHILD1"])
    g.lookup_departures.assert_called_once_with([("f-sf~bay~area~rg", "CHILD1")], 3600)
    assert mock_http.call_count == 0
    assert result["stops"][0]["departures"][0]["headsign"] == "Y"


def test_synthetic_id_stale_rt_still_local():
    g = _synthetic_gtfs()
    g.refresh_if_stale.return_value = _fresh_outcome(rt_age=99999, static_age=3600)
    with patch.object(proxy, "gtfs511", g), patch.object(proxy, "_GTFS511_IMPORTED", True), \
         patch.object(proxy, "_http_get_json", side_effect=AssertionError("no upstream")):
        proxy.get_departures_by_onestop_ids(["511:f-sf~bay~area~rg:CHILD1"])
    g.lookup_departures.assert_called_once()


def test_synthetic_id_static_missing_returns_empty():
    g = _synthetic_gtfs(static_present=False)
    with patch.object(proxy, "gtfs511", g), patch.object(proxy, "_GTFS511_IMPORTED", True), \
         patch.object(proxy, "_http_get_json", side_effect=AssertionError("no upstream")):
        result = proxy.get_departures_by_onestop_ids(["511:f-sf~bay~area~rg:CHILD1"])
    g.lookup_departures.assert_not_called()
    assert result["stops"][0]["departures"] == []


def test_synthetic_id_with_colon_in_stop_id():
    g = _synthetic_gtfs()
    with patch.object(proxy, "gtfs511", g), patch.object(proxy, "_GTFS511_IMPORTED", True), \
         patch.object(proxy, "_http_get_json", side_effect=AssertionError("no upstream")):
        proxy.get_departures_by_onestop_ids(["511:f-sf~bay~area~rg:A:B"])
    g.lookup_departures.assert_called_once_with([("f-sf~bay~area~rg", "A:B")], 3600)


def test_malformed_synthetic_ids_empty_no_upstream():
    g = _synthetic_gtfs()
    for oid in ("511:", "511:unknown-feed:STOP"):
        g.lookup_departures.reset_mock()
        with patch.object(proxy, "gtfs511", g), patch.object(proxy, "_GTFS511_IMPORTED", True), \
             patch.object(proxy, "_http_get_json",
                          side_effect=_make_http_mock({"stops": []})):
            result = proxy.get_departures_by_onestop_ids([oid])
        g.lookup_departures.assert_not_called()
        assert result["stops"][0]["departures"] == []


def test_mixed_batch_synthetic_plus_non_bay_area():
    g = _synthetic_gtfs()
    calls = []

    def mock(url, params=None, headers=None, timeout=None, allow_404=False):
        calls.append(url)
        if "/stops?" in (url + "?"):
            return _STOPS_NYC
        if "/stops/" in url and "/departures" in url:
            return _FAKE_TL_DEPARTURES
        raise AssertionError(url)

    with patch.object(proxy, "gtfs511", g), patch.object(proxy, "_GTFS511_IMPORTED", True), \
         patch.object(proxy, "_http_get_json", side_effect=mock):
        proxy.get_departures_by_onestop_ids(["511:f-sf~bay~area~rg:CHILD1", "s-nyc-grandcentral"])
    resolve = [u for u in calls if "/stops?" in (u + "?")]
    dep = [u for u in calls if "/departures" in u]
    assert len(resolve) == 1  # only the non-Bay-Area oid resolves
    assert len(dep) == 1
