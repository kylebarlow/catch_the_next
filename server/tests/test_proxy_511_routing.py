"""Routing tests for proxy.get_departures_by_onestop_ids — verify Bay Area
stops are served from the gtfs511 path and non-Bay-Area stops continue to
hit Transitland."""
import os
os.environ.setdefault("TRANSITLAND_API_KEY", "test-key")
os.environ.setdefault("APP_API_KEYS", "test-key")

import sys
from unittest.mock import patch, MagicMock

import proxy


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
    fake_gtfs.refresh_if_stale.return_value = None
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
    fake_gtfs.refresh_if_stale.side_effect = RuntimeError("simulated 511 outage")

    with patch.object(proxy, "gtfs511", fake_gtfs), \
         patch.object(proxy, "_GTFS511_IMPORTED", True), \
         patch.object(proxy, "_http_get_json", side_effect=_make_http_mock(_STOPS_BART)) as mock_http:
        result = proxy.get_departures_by_onestop_ids(["s-bart-embarcadero"])

    departures_calls = [c for c in mock_http.call_args_list if "/departures" in c.args[0]]
    assert len(departures_calls) == 1  # Transitland was hit as fallback.
    assert result["stops"][0]["onestop_id"] == "s-bart-embarcadero"
    err = capsys.readouterr().err
    assert "gtfs511 fallback" in err
