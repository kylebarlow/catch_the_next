"""Public-scope (511-only) enforcement: a public key must never reach Transitland.

Asserts the hard gate in proxy.py — public scope serves local/synthetic data or empty,
and any path that would call Transitland (_upstream_get / _resolve_stop_info / _tl_departures)
either is never invoked or raises 403 forbidden_upstream.
"""
import os
os.environ.setdefault("TRANSITLAND_API_KEY", "real-upstream-key")
os.environ.setdefault("APP_API_KEYS", "app-key")
os.environ.setdefault("APP_API_KEYS_PUBLIC", "pub-key")
os.environ.setdefault("TRANSITLAND_BASE_URL", "http://mock-transitland")
os.environ.setdefault("CACHE_DB_PATH", ":memory:")

import pytest
from unittest.mock import patch
import bottle
import proxy
from proxy import SCOPE_PUBLIC, SCOPE_FULL


def test_get_stops_public_outside_bay_area_returns_empty_without_upstream():
    # Coords far outside the Bay Area; full scope would hit Transitland.
    with patch("proxy._upstream_get") as upstream, \
         patch("proxy.gtfs511.in_bay_area", return_value=False):
        result = proxy.get_stops(40.7128, -74.0060, 500, 20, scope=SCOPE_PUBLIC)
    assert result == {"stops": []}
    upstream.assert_not_called()


def test_get_stops_public_in_bay_area_serves_local_without_upstream():
    rows = [{"stop_id": "12345", "stop_name": "Local Stop", "stop_lat": 37.78, "stop_lon": -122.41}]
    with patch("proxy._upstream_get") as upstream, \
         patch("proxy.gtfs511.in_bay_area", return_value=True), \
         patch("proxy.gtfs511.nearby_stops", return_value=rows), \
         patch("proxy.gtfs511.metadata_for", return_value={}), \
         patch("proxy.gtfs511.REGIONAL_FEED_ID", "f-sf~bay~area~rg"), \
         patch("proxy._bay_area_feed", return_value=True):
        result = proxy.get_stops(37.78, -122.41, 500, 20, scope=SCOPE_PUBLIC)
    assert len(result["stops"]) == 1
    assert result["stops"][0]["onestop_id"].startswith("511:")
    upstream.assert_not_called()


def test_get_departures_integer_path_public_is_forbidden():
    with pytest.raises(bottle.HTTPResponse) as exc:
        proxy.get_departures(123, 3600, scope=SCOPE_PUBLIC)
    assert exc.value.status_code == 403
    assert "forbidden_upstream" in exc.value.body


def test_batch_public_never_resolves_or_calls_transitland():
    # A non-synthetic onestop_id under public scope must NOT resolve via Transitland;
    # it returns empty instead. A synthetic id serves locally.
    syn = "511:f-sf~bay~area~rg:99999"
    local_resp = {"onestop_id": syn, "departures": [{"route_short_name": "N"}], "alerts": []}
    with patch("proxy._resolve_stop_info") as resolve, \
         patch("proxy._upstream_get") as upstream, \
         patch("proxy._bay_area_feed", return_value=True), \
         patch("proxy._refresh_511_state", return_value={"static_present": True, "rt_fresh": False, "rt_age": None}), \
         patch("proxy._local_departures", return_value=local_resp):
        result = proxy.get_departures_by_onestop_ids([syn, "s-nationwide"], 3600, scope=SCOPE_PUBLIC)

    resolve.assert_not_called()
    upstream.assert_not_called()
    by_id = {s["onestop_id"]: s for s in result["stops"]}
    assert by_id[syn]["departures"] == [{"route_short_name": "N"}]
    assert by_id["s-nationwide"] == {"onestop_id": "s-nationwide", "departures": [], "alerts": []}


def test_upstream_get_choke_point_blocks_public():
    with pytest.raises(bottle.HTTPResponse) as exc:
        proxy._upstream_get("stops", {"lat": 1, "lon": 2}, scope=SCOPE_PUBLIC)
    assert exc.value.status_code == 403


def test_full_scope_still_reaches_transitland():
    # Regression guard: full scope is unchanged — _upstream_get proceeds.
    with patch("proxy._http_get_json", return_value={"stops": []}) as http:
        proxy._upstream_get("stops", {"onestop_id": "s-x", "limit": 1}, scope=SCOPE_FULL)
    http.assert_called_once()
