import os
os.environ.setdefault("TRANSITLAND_API_KEY", "real-upstream-key")
os.environ.setdefault("APP_API_KEYS", "app-key")
os.environ.setdefault("TRANSITLAND_BASE_URL", "http://mock-transitland")
os.environ.setdefault("CACHE_DB_PATH", ":memory:")

import json
import time
import pytest
from unittest.mock import patch, MagicMock
import proxy


# ── HTTP mock helpers ──────────────────────────────────────────────────────────
# Tests patch proxy._http_get_json directly, since proxy now uses urllib
# (not requests). Each test controls what _http_get_json returns per URL.

def _make_http_mock(*url_responses, default=None, capture=None):
    """Return a side_effect callable for proxy._http_get_json.

    *url_responses* is a sequence of (url_fragment, response_or_callable) pairs
    tried in order. If the URL contains *url_fragment*, return the paired value
    (or call it with (url, params, headers) if callable).

    If *capture* is a list it accumulates {"url":…, "params":…, "headers":…}
    dicts for later inspection.
    """
    def side_effect(url, params=None, headers=None, timeout=None, allow_404=False):
        if capture is not None:
            capture.append({"url": url, "params": dict(params or {}), "headers": dict(headers or {})})
        for fragment, resp in url_responses:
            if fragment in url:
                if callable(resp):
                    return resp(url, params or {}, headers or {})
                return resp
        if default is not None:
            return default
        raise AssertionError(f"Unexpected _http_get_json call: {url!r} params={params}")
    return side_effect


# ── Fixture data ───────────────────────────────────────────────────────────────

STOPS_RESPONSE = {
    "stops": [
        {
            "id": 123,
            "stop_id": "S1",
            "stop_name": "Test Stop",
            "geometry": {"coordinates": [-122.4, 37.8]},
            "onestop_id": "s-test",
        }
    ]
}

STOPS_RESPONSE_WITH_FEED = {
    "stops": [
        {
            "id": 456,
            "stop_id": "S2",
            "stop_name": "Feed Stop",
            "geometry": {"coordinates": [-122.4, 37.8]},
            "onestop_id": "s-feed",
            "feed_version": {
                "feed": {
                    "onestop_id": "f-9q9-testfeed",
                    "name": "Test Transit Agency",
                    "license": {
                        "spdx_identifier": "CC-BY-4.0",
                        "attribution_text": "Data provided by Test Transit Agency",
                        "attribution_instructions": "Please credit Test Transit Agency",
                        "use_without_attribution": "no",
                        "url": "https://example.com/license",
                    },
                }
            },
        }
    ]
}

DEPARTURES_RESPONSE = {
    "stops": [
        {
            "departures": [
                {
                    "departure": {"scheduled_utc": "2099-01-01T12:00:00Z"},
                    "trip": {
                        "trip_headsign": "Downtown",
                        "route": {"route_short_name": "42"},
                    },
                    "schedule_relationship": "SCHEDULED",
                }
            ],
            "children": [],
        }
    ]
}

DEPARTURES_RESPONSE_WITH_AGENCY = {
    "stops": [
        {
            "departures": [
                {
                    "departure": {"scheduled_utc": "2099-01-01T12:00:00Z"},
                    "trip": {
                        "trip_headsign": "Downtown",
                        "route": {
                            "route_short_name": "42",
                            "agency": {
                                "agency_name": "Test Transit Authority",
                                "agency_url": "https://example.com",
                                "onestop_id": "o-9q9-testtransit",
                            },
                            "feed_version": {
                                "feed": {
                                    "onestop_id": "f-9q9-testfeed",
                                    "name": "Test Transit GTFS",
                                    "license": {
                                        "spdx_identifier": "CC-BY-4.0",
                                        "attribution_text": "Data from Test Transit",
                                        "attribution_instructions": "Please credit Test Transit in your app",
                                        "use_without_attribution": "no",
                                        "url": "https://example.com/license",
                                    },
                                }
                            },
                        },
                    },
                    "schedule_relationship": "SCHEDULED",
                }
            ],
            "children": [],
        }
    ]
}


# ── get_stops ──────────────────────────────────────────────────────────────────

def test_get_stops_returns_shaped_data():
    with patch("proxy._http_get_json", side_effect=_make_http_mock(("stops", STOPS_RESPONSE))):
        result = proxy.get_stops(37.8, -122.4)

    assert "stops" in result
    assert result["stops"][0]["stop_name"] == "Test Stop"
    assert result["stops"][0]["lat"] == 37.8
    assert result["stops"][0]["lon"] == -122.4


def test_get_stops_injects_upstream_key():
    calls = []
    with patch("proxy._http_get_json", side_effect=_make_http_mock(("stops", STOPS_RESPONSE), capture=calls)):
        proxy.get_stops(37.8, -122.4)

    assert any("apikey" in c["params"] for c in calls)
    assert any(c["params"].get("apikey") == "real-upstream-key" for c in calls)


def test_get_stops_does_not_leak_key_in_response():
    with patch("proxy._http_get_json", side_effect=_make_http_mock(("stops", STOPS_RESPONSE))):
        result = proxy.get_stops(37.8, -122.4)

    assert "real-upstream-key" not in json.dumps(result)


def test_get_stops_clamps_radius():
    calls = []
    with patch("proxy._http_get_json", side_effect=_make_http_mock(("stops", STOPS_RESPONSE), capture=calls)):
        proxy.get_stops(37.8, -122.4, radius=99999)

    stops_call = next(c for c in calls if "stops" in c["url"])
    assert int(stops_call["params"]["radius"]) <= 5000


def test_get_stops_does_not_leak_internal_distance_field():
    with patch("proxy._http_get_json", side_effect=_make_http_mock(("stops", STOPS_RESPONSE))):
        result = proxy.get_stops(37.8, -122.4)

    assert all("_dist_m" not in s for s in result["stops"])


DUBOCE_PARK_LIKE_RESPONSE = {
    "stops": [
        {"id": 1, "stop_id": "A1", "stop_name": "14th St & Castro St",
         "geometry": {"coordinates": [-122.4350, 37.7660]}},
        {"id": 2, "stop_id": "A2", "stop_name": "14th St & Church St",
         "geometry": {"coordinates": [-122.4291, 37.7660]}},
        {"id": 3, "stop_id": "A3", "stop_name": "Castro St & Duboce Ave",
         "geometry": {"coordinates": [-122.4350, 37.7672]}},
        {"id": 100, "stop_id": "N1", "stop_name": "Duboce St/Noe St/Duboce Park",
         "geometry": {"coordinates": [-122.43356, 37.76936]}},
        {"id": 101, "stop_id": "N2", "stop_name": "Sunset Tunnel East Portal",
         "geometry": {"coordinates": [-122.43351, 37.76929]}},
    ]
}


def test_get_stops_sorts_by_distance_not_alphabetical():
    with patch("proxy._http_get_json", side_effect=_make_http_mock(("stops", DUBOCE_PARK_LIKE_RESPONSE))):
        result = proxy.get_stops(37.76955, -122.43320, radius=600, limit=20)

    names = [s["stop_name"] for s in result["stops"]]
    assert names[0] in {"Duboce St/Noe St/Duboce Park", "Sunset Tunnel East Portal"}
    assert names[1] in {"Duboce St/Noe St/Duboce Park", "Sunset Tunnel East Portal"}


def test_get_stops_low_limit_keeps_closest_not_alphabetical():
    with patch("proxy._http_get_json", side_effect=_make_http_mock(("stops", DUBOCE_PARK_LIKE_RESPONSE))):
        result = proxy.get_stops(37.76955, -122.43320, radius=600, limit=2)

    names = {s["stop_name"] for s in result["stops"]}
    assert names == {"Duboce St/Noe St/Duboce Park", "Sunset Tunnel East Portal"}


def test_get_stops_requests_wide_candidate_set_upstream():
    calls = []
    with patch("proxy._http_get_json", side_effect=_make_http_mock(("stops", STOPS_RESPONSE), capture=calls)):
        proxy.get_stops(37.8, -122.4, limit=5)

    stops_call = next(c for c in calls if "stops" in c["url"] and "departures" not in c["url"])
    assert int(stops_call["params"]["limit"]) >= 100


def test_get_stops_skips_entries_missing_geometry():
    response = {
        "stops": [
            {"id": 1, "stop_id": "A", "stop_name": "Has Geom",
             "geometry": {"coordinates": [-122.4, 37.8]}},
            {"id": 2, "stop_id": "B", "stop_name": "No Geom",
             "geometry": {"coordinates": [None, None]}},
        ]
    }
    with patch("proxy._http_get_json", side_effect=_make_http_mock(("stops", response))):
        result = proxy.get_stops(37.8, -122.4)

    assert [s["stop_name"] for s in result["stops"]] == ["Has Geom"]


def test_get_stops_passes_through_feed_attribution():
    with patch("proxy._http_get_json", side_effect=_make_http_mock(("stops", STOPS_RESPONSE_WITH_FEED))):
        result = proxy.get_stops(37.8, -122.4)

    stop = result["stops"][0]
    assert stop["feed_onestop_id"] == "f-9q9-testfeed"
    assert stop["feed_name"] == "Test Transit Agency"
    assert stop["attribution_text"] == "Data provided by Test Transit Agency"
    assert stop["attribution_instructions"] == "Please credit Test Transit Agency"
    assert stop["use_without_attribution"] is False
    assert stop["license_spdx"] == "CC-BY-4.0"
    assert stop["license_url"] == "https://example.com/license"


def test_get_stops_attribution_fields_are_none_when_no_feed_version():
    with patch("proxy._http_get_json", side_effect=_make_http_mock(("stops", STOPS_RESPONSE))):
        result = proxy.get_stops(37.8, -122.4)

    stop = result["stops"][0]
    assert stop["feed_onestop_id"] is None
    assert stop["feed_name"] is None
    assert stop["attribution_text"] is None


def test_upstream_500_raises_http_response():
    import bottle
    import urllib.error

    def raise_500(url, params=None, headers=None, timeout=None, allow_404=False):
        raise bottle.HTTPResponse(
            body=json.dumps({"error": "upstream", "status": 500}),
            status=502,
            headers={"Content-Type": "application/json"},
        )

    with patch("proxy._http_get_json", side_effect=raise_500):
        with pytest.raises(bottle.HTTPResponse) as exc:
            proxy.get_stops(37.8, -122.4)
    assert exc.value.status_code == 502


def test_proxy_uses_descriptive_user_agent():
    calls = []
    with patch("proxy._http_get_json", side_effect=_make_http_mock(("stops", STOPS_RESPONSE), capture=calls)):
        proxy.get_stops(37.8, -122.4)

    assert proxy._USER_AGENT.startswith("CatchTheNext")


def test_get_stops_radius_capped_at_5000():
    calls = []
    with patch("proxy._http_get_json", side_effect=_make_http_mock(("stops", STOPS_RESPONSE), capture=calls)):
        proxy.get_stops(37.8, -122.4, radius=99999)

    stops_call = next(c for c in calls if "stops" in c["url"] and "departures" not in c["url"])
    assert int(stops_call["params"]["radius"]) <= 5000


# ── get_stops local 511 path + grid cache ───────────────────────────────────

_LOCAL_ROWS = [
    {"stop_id": "CHILD1", "stop_name": "Platform 1", "stop_lat": 37.7705,
     "stop_lon": -122.4205, "parent_station": "PARENT", "location_type": "0"},
    {"stop_id": "BAD,ID", "stop_name": "Comma Stop", "stop_lat": 37.7706,
     "stop_lon": -122.4206, "parent_station": "", "location_type": "0"},
]


def test_get_stops_local_bay_area_path(monkeypatch):
    monkeypatch.setattr(proxy.gtfs511, "in_bay_area", lambda lat, lon: True)
    monkeypatch.setattr(proxy.gtfs511, "nearby_stops", lambda lat, lon, r, lim: _LOCAL_ROWS)
    # _http_get_json must never be called on the local path.
    with patch("proxy._http_get_json", side_effect=AssertionError("no upstream")):
        result = proxy.get_stops(37.77, -122.42)

    # The comma stop_id is skipped (can't be a synthetic id).
    assert len(result["stops"]) == 1
    s = result["stops"][0]
    assert set(s.keys()) == {
        "id", "stop_id", "stop_name", "lat", "lon", "onestop_id",
        "feed_onestop_id", "feed_name", "attribution_text",
        "attribution_instructions", "use_without_attribution",
        "license_spdx", "license_url",
    }
    assert s["onestop_id"] == "511:f-sf~bay~area~rg:CHILD1"
    assert s["id"] < 0  # negative stable synthetic id
    assert s["feed_onestop_id"] == "f-sf~bay~area~rg"
    assert "511" in (s["attribution_text"] or "")


def test_get_stops_local_none_falls_through_to_tl(monkeypatch):
    monkeypatch.setattr(proxy.gtfs511, "in_bay_area", lambda lat, lon: True)
    monkeypatch.setattr(proxy.gtfs511, "nearby_stops", lambda lat, lon, r, lim: None)
    with patch("proxy._http_get_json", side_effect=_make_http_mock(("stops", STOPS_RESPONSE))):
        result = proxy.get_stops(37.77, -122.42, radius=5000)
    assert result["stops"][0]["onestop_id"] == "s-test"


def test_get_stops_outside_bbox_uses_tl(monkeypatch):
    monkeypatch.setattr(proxy.gtfs511, "in_bay_area", lambda lat, lon: False)
    with patch("proxy._http_get_json", side_effect=_make_http_mock(("stops", STOPS_RESPONSE))):
        result = proxy.get_stops(37.8, -122.4)
    assert result["stops"][0]["onestop_id"] == "s-test"


def test_get_stops_grid_same_cell_one_upstream_call(monkeypatch):
    monkeypatch.setattr(proxy.gtfs511, "in_bay_area", lambda lat, lon: False)
    calls = []
    with patch("proxy._http_get_json", side_effect=_make_http_mock(("stops", STOPS_RESPONSE), capture=calls)):
        proxy.get_stops(37.7700, -122.4200, radius=200)
        proxy.get_stops(37.7701, -122.4201, radius=200)  # same grid cell
    stops_calls = [c for c in calls if "stops" in c["url"] and "departures" not in c["url"]]
    assert len(stops_calls) == 1


def test_get_stops_grid_adjacent_cells_two_calls(monkeypatch):
    monkeypatch.setattr(proxy.gtfs511, "in_bay_area", lambda lat, lon: False)
    calls = []
    with patch("proxy._http_get_json", side_effect=_make_http_mock(("stops", STOPS_RESPONSE), capture=calls)):
        proxy.get_stops(37.7700, -122.4200, radius=200)
        proxy.get_stops(37.7800, -122.4200, radius=200)  # different cell
    stops_calls = [c for c in calls if "stops" in c["url"] and "departures" not in c["url"]]
    assert len(stops_calls) == 2


def test_get_stops_grid_upstream_radius_includes_slack(monkeypatch):
    monkeypatch.setattr(proxy.gtfs511, "in_bay_area", lambda lat, lon: False)
    calls = []
    with patch("proxy._http_get_json", side_effect=_make_http_mock(("stops", STOPS_RESPONSE), capture=calls)):
        proxy.get_stops(37.77, -122.42, radius=500)
    stops_call = next(c for c in calls if "stops" in c["url"] and "departures" not in c["url"])
    assert int(stops_call["params"]["radius"]) == 800  # 500 + 300 slack


def test_get_stops_grid_filters_beyond_requested_radius(monkeypatch):
    monkeypatch.setattr(proxy.gtfs511, "in_bay_area", lambda lat, lon: False)
    # Upstream returns a far stop (within slack but beyond requested radius).
    far = {"stops": [{
        "id": 1, "stop_id": "FAR", "stop_name": "Far",
        "geometry": {"coordinates": [-122.4, 37.78]},  # ~1.1km from 37.77
    }]}
    with patch("proxy._http_get_json", side_effect=_make_http_mock(("stops", far))):
        result = proxy.get_stops(37.77, -122.42, radius=300)
    assert result["stops"] == []  # filtered: beyond requested radius


# ── get_departures ─────────────────────────────────────────────────────────────

DEPARTURES_RESPONSE_NULL_FIELDS = {
    "stops": [
        {
            "departures": [
                {
                    "departure": None,
                    "trip": None,
                    "departure_time": "25:00:00",
                    "schedule_relationship": "SCHEDULED",
                }
            ],
            "children": None,
        }
    ]
}


def test_get_departures_handles_null_departure_and_children():
    with patch("proxy._http_get_json", side_effect=_make_http_mock(("/departures", DEPARTURES_RESPONSE_NULL_FIELDS))):
        result = proxy.get_departures(42, next_seconds=3600)
    assert "departures" in result
    assert result["departures"] == []


def test_get_departures_returns_sorted():
    with patch("proxy._http_get_json", side_effect=_make_http_mock(("/departures", DEPARTURES_RESPONSE))):
        result = proxy.get_departures(42, next_seconds=3600)

    deps = result["departures"]
    minutes = [d["scheduled_departure_minutes"] for d in deps]
    assert minutes == sorted(minutes)


DEPARTURES_RESPONSE_WITH_ESTIMATE = {
    "stops": [
        {
            "departures": [
                {
                    "departure": {
                        "scheduled_utc": "2099-01-01T12:00:00Z",
                        "estimated_utc": "2099-01-01T12:03:00Z",
                        "scheduled_local": "12:00",
                        "estimated_local": "12:03",
                    },
                    "trip": {
                        "trip_headsign": "Downtown",
                        "route": {"route_short_name": "42"},
                    },
                    "schedule_relationship": "SCHEDULED",
                }
            ],
            "children": [],
        }
    ]
}

DEPARTURES_RESPONSE_STATIC_NO_ESTIMATE = {
    "stops": [
        {
            "departures": [
                {
                    "departure": {"scheduled_utc": "2099-01-01T12:00:00Z"},
                    "trip": {
                        "trip_headsign": "Downtown",
                        "route": {"route_short_name": "42"},
                    },
                    "schedule_relationship": "STATIC",
                }
            ],
            "children": [],
        }
    ]
}

DEPARTURES_RESPONSE_SCHEDULED_ONLY = {
    "stops": [
        {
            "departures": [
                {
                    "departure": {"scheduled_utc": "2099-01-01T12:00:00Z"},
                    "trip": {
                        "trip_headsign": "Downtown",
                        "route": {"route_short_name": "42"},
                    },
                    "schedule_relationship": "SCHEDULED",
                }
            ],
            "children": [],
        }
    ]
}


def test_departure_with_estimate_is_live():
    with patch("proxy._http_get_json", side_effect=_make_http_mock(("/departures", DEPARTURES_RESPONSE_WITH_ESTIMATE))):
        result = proxy.get_departures(42, next_seconds=3600)

    dep = result["departures"][0]
    assert dep["time_source"] == "LIVE"
    assert dep["live_departure_utc"] == "2099-01-01T12:03:00Z"
    assert dep["live_departure_time"] == "12:03"
    assert dep["scheduled_departure_utc"] == "2099-01-01T12:00:00Z"
    assert dep["scheduled_departure_minutes"] is not None
    assert dep["live_departure_minutes"] is not None


def test_departure_without_estimate_is_scheduled():
    with patch("proxy._http_get_json", side_effect=_make_http_mock(("/departures", DEPARTURES_RESPONSE_SCHEDULED_ONLY))):
        result = proxy.get_departures(42, next_seconds=3600)

    dep = result["departures"][0]
    assert dep["time_source"] == "SCHEDULED"
    assert dep["live_departure_utc"] is None
    assert dep["live_departure_minutes"] is None
    assert dep["scheduled_departure_utc"] == "2099-01-01T12:00:00Z"


def test_static_without_estimate_is_scheduled():
    with patch("proxy._http_get_json", side_effect=_make_http_mock(("/departures", DEPARTURES_RESPONSE_STATIC_NO_ESTIMATE))):
        result = proxy.get_departures(42, next_seconds=3600)

    dep = result["departures"][0]
    assert dep["time_source"] == "SCHEDULED"


def test_live_departure_sorts_by_live_minutes():
    response = {
        "stops": [
            {
                "departures": [
                    {
                        "departure": {
                            "scheduled_utc": "2099-01-01T12:20:00Z",
                            "estimated_utc": "2099-01-01T12:18:00Z",
                        },
                        "trip": {"trip_headsign": "Late", "route": {"route_short_name": "1"}},
                        "schedule_relationship": "SCHEDULED",
                    },
                    {
                        "departure": {"scheduled_utc": "2099-01-01T12:05:00Z"},
                        "trip": {"trip_headsign": "Early", "route": {"route_short_name": "2"}},
                        "schedule_relationship": "SCHEDULED",
                    },
                ],
                "children": [],
            }
        ]
    }
    with patch("proxy._http_get_json", side_effect=_make_http_mock(("/departures", response))):
        result = proxy.get_departures(42, next_seconds=3600)

    deps = result["departures"]
    minutes = []
    for d in deps:
        if d["time_source"] == "LIVE" and d["live_departure_minutes"] is not None:
            minutes.append(d["live_departure_minutes"])
        else:
            minutes.append(d["scheduled_departure_minutes"])
    assert minutes == sorted(minutes)


def test_departure_without_utc_is_skipped():
    response = {
        "stops": [
            {
                "departures": [
                    {
                        "departure": None,
                        "trip": {
                            "trip_headsign": "Fallback",
                            "route": {"route_short_name": "99"},
                        },
                        "departure_time": "25:00:00",
                        "schedule_relationship": "SCHEDULED",
                    }
                ],
                "children": None,
            }
        ]
    }
    with patch("proxy._http_get_json", side_effect=_make_http_mock(("/departures", response))):
        result = proxy.get_departures(42, next_seconds=86400)

    assert result["departures"] == []


def test_get_departures_passes_through_agency_and_feed():
    with patch("proxy._http_get_json", side_effect=_make_http_mock(("/departures", DEPARTURES_RESPONSE_WITH_AGENCY))):
        result = proxy.get_departures(42, next_seconds=3600)

    dep = result["departures"][0]
    assert dep["agency_name"] == "Test Transit Authority"
    assert dep["feed_onestop_id"] == "f-9q9-testfeed"
    assert dep["feed_name"] == "Test Transit GTFS"
    assert dep["attribution_text"] == "Data from Test Transit"
    assert dep["attribution_instructions"] == "Please credit Test Transit in your app"
    assert dep["use_without_attribution"] is False
    assert dep["license_spdx"] == "CC-BY-4.0"
    assert dep["time_source"] == "SCHEDULED"
    assert dep["scheduled_departure_utc"] == "2099-01-01T12:00:00Z"


def test_get_departures_attribution_fields_none_when_no_agency():
    with patch("proxy._http_get_json", side_effect=_make_http_mock(("/departures", DEPARTURES_RESPONSE))):
        result = proxy.get_departures(42, next_seconds=3600)

    dep = result["departures"][0]
    assert dep["agency_name"] is None
    assert dep["feed_onestop_id"] is None
    assert dep["attribution_text"] is None


def test_get_departures_sends_include_alerts():
    calls = []
    with patch("proxy._http_get_json", side_effect=_make_http_mock(("/departures", DEPARTURES_RESPONSE), capture=calls)):
        proxy.get_departures(42, next_seconds=3600)

    dep_call = next(c for c in calls if "/departures" in c["url"])
    assert dep_call["params"].get("include_alerts") == "true"


# ── batch departures ───────────────────────────────────────────────────────────

RESOLVE_10 = {"stops": [{"id": 10, "onestop_id": "s-test-10", "stop_name": "Stop 10", "geometry": {"coordinates": [-122.4, 37.8]}}]}
RESOLVE_20 = {"stops": [{"id": 20, "onestop_id": "s-test-20", "stop_name": "Stop 20", "geometry": {"coordinates": [-122.4, 37.8]}}]}
RESOLVE_42 = {"stops": [{"id": 42, "onestop_id": "s-test-42", "stop_name": "Stop 42", "geometry": {"coordinates": [-122.4, 37.8]}}]}


def _resolve_by_oid(url, params, headers):
    """Simulate Transitland: match exactly one onestop_id at a time."""
    oid = params.get("onestop_id", "")
    catalog = {
        "s-test-10": RESOLVE_10,
        "s-test-20": RESOLVE_20,
        "s-test-30": {"stops": [{"id": 30, "onestop_id": "s-test-30", "stop_name": "Stop 30", "geometry": {"coordinates": [-122.4, 37.8]}}]},
        "s-test-42": RESOLVE_42,
        "s-abc-mystation": {"stops": [{"id": 99, "onestop_id": "s-abc-mystation", "stop_name": "My Station", "geometry": {"coordinates": [-122.4, 37.8]}}]},
    }
    return catalog.get(oid, {"stops": []})


def _batch_http(dep_responses_by_id=None, capture=None):
    """Build a _http_get_json mock for batch-departures tests."""
    dep_responses_by_id = dep_responses_by_id or {}

    def side_effect(url, params=None, headers=None, timeout=None, allow_404=False):
        if capture is not None:
            capture.append({"url": url, "params": dict(params or {}), "headers": dict(headers or {})})
        if "stops" in url and "/departures" not in url:
            return _resolve_by_oid(url, params or {}, headers or {})
        for stop_id, resp in dep_responses_by_id.items():
            if f"stops/{stop_id}/departures" in url:
                return resp
        return DEPARTURES_RESPONSE
    return side_effect


def test_get_departures_by_onestop_ids_returns_grouped_results():
    with patch("proxy._http_get_json", side_effect=_batch_http({"s-test-10": DEPARTURES_RESPONSE, "s-test-20": DEPARTURES_RESPONSE_WITH_AGENCY})):
        result = proxy.get_departures_by_onestop_ids(["s-test-10", "s-test-20"], next_seconds=3600)

    assert "stops" in result
    assert len(result["stops"]) == 2
    ids = [s["onestop_id"] for s in result["stops"]]
    assert "s-test-10" in ids
    assert "s-test-20" in ids
    for stop in result["stops"]:
        assert "departures" in stop
        assert len(stop["departures"]) > 0


def test_get_departures_by_onestop_ids_preserves_request_order():
    with patch("proxy._http_get_json", side_effect=_batch_http()):
        result = proxy.get_departures_by_onestop_ids(["s-test-30", "s-test-10"], next_seconds=3600)

    assert result["stops"][0]["onestop_id"] == "s-test-30"
    assert result["stops"][1]["onestop_id"] == "s-test-10"


def test_get_departures_by_onestop_ids_returns_empty_when_stop_not_resolved():
    with patch("proxy._http_get_json", side_effect=_batch_http({"s-test-10": DEPARTURES_RESPONSE})):
        result = proxy.get_departures_by_onestop_ids(["s-test-10", "s-test-notfound"], next_seconds=3600)

    found = next(s for s in result["stops"] if s["onestop_id"] == "s-test-10")
    not_found = next(s for s in result["stops"] if s["onestop_id"] == "s-test-notfound")
    assert len(found["departures"]) > 0
    assert not_found["departures"] == []
    assert not_found["alerts"] == []


def test_get_departures_by_onestop_ids_returns_empty_departures_for_stop_with_none():
    response_no_deps = {"stops": [{"departures": None, "children": None}]}
    with patch("proxy._http_get_json", side_effect=_batch_http({"s-test-10": DEPARTURES_RESPONSE, "s-test-20": response_no_deps})):
        result = proxy.get_departures_by_onestop_ids(["s-test-10", "s-test-20"], next_seconds=3600)

    stop10 = next(s for s in result["stops"] if s["onestop_id"] == "s-test-10")
    stop20 = next(s for s in result["stops"] if s["onestop_id"] == "s-test-20")
    assert len(stop10["departures"]) > 0
    assert stop20["departures"] == []


def test_get_departures_by_onestop_ids_applies_next_to_departure_calls():
    calls = []
    with patch("proxy._http_get_json", side_effect=_batch_http(capture=calls)):
        proxy.get_departures_by_onestop_ids(["s-test-10", "s-test-20"], next_seconds=1800)

    dep_calls = [c for c in calls if "/departures" in c["url"]]
    for c in dep_calls:
        assert c["params"].get("next") == 1800, f"expected next=1800, got {c['params']}"


def test_get_departures_by_onestop_ids_shaping_matches_single_stop():
    with patch("proxy._http_get_json", side_effect=_make_http_mock(("/departures", DEPARTURES_RESPONSE_WITH_AGENCY))):
        single = proxy.get_departures(42, next_seconds=3600)
    with patch("proxy._http_get_json", side_effect=_batch_http({"s-test-42": DEPARTURES_RESPONSE_WITH_AGENCY})):
        batch = proxy.get_departures_by_onestop_ids(["s-test-42"], next_seconds=3600)

    assert single["departures"] == batch["stops"][0]["departures"]


def test_shape_departures_is_used_by_get_departures():
    with patch("proxy._http_get_json", side_effect=_make_http_mock(("/departures", DEPARTURES_RESPONSE_WITH_ESTIMATE))):
        result = proxy.get_departures(42, next_seconds=3600)

    dep = result["departures"][0]
    assert dep["time_source"] == "LIVE"
    assert dep["live_departure_utc"] == "2099-01-01T12:03:00Z"


def test_get_departures_by_onestop_ids_resolves_then_fetches():
    calls = []
    with patch("proxy._http_get_json", side_effect=_batch_http({"s-abc-mystation": DEPARTURES_RESPONSE}, capture=calls)):
        result = proxy.get_departures_by_onestop_ids(["s-abc-mystation"], next_seconds=3600)

    assert result["stops"][0]["onestop_id"] == "s-abc-mystation"
    assert len(result["stops"][0]["departures"]) > 0
    resolve_calls = [c for c in calls if "stops" in c["url"] and "/departures" not in c["url"]]
    assert any(c["params"].get("onestop_id") == "s-abc-mystation" for c in resolve_calls)


def test_get_departures_by_onestop_ids_resolves_each_id_individually():
    calls = []
    with patch("proxy._http_get_json", side_effect=_batch_http({"s-test-10": DEPARTURES_RESPONSE, "s-test-20": DEPARTURES_RESPONSE}, capture=calls)):
        result = proxy.get_departures_by_onestop_ids(["s-test-10", "s-test-20"], next_seconds=3600)

    resolve_calls = [c for c in calls if "stops" in c["url"] and "/departures" not in c["url"]]
    assert len(resolve_calls) == 2
    for c in resolve_calls:
        oid = c["params"].get("onestop_id", "")
        assert "," not in oid, f"onestop_id must not be comma-joined: {oid!r}"

    assert len(result["stops"]) == 2
    assert len(result["stops"][0]["departures"]) > 0
    assert len(result["stops"][1]["departures"]) > 0


def test_get_departures_by_onestop_ids_sends_include_alerts():
    calls = []
    with patch("proxy._http_get_json", side_effect=_batch_http(capture=calls)):
        proxy.get_departures_by_onestop_ids(["s-test-10"], next_seconds=3600)

    dep_calls = [c for c in calls if "/departures" in c["url"]]
    assert all(c["params"].get("include_alerts") == "true" for c in dep_calls)


def test_departures_url_uses_onestop_id_with_one_resolve_one_departures():
    """The Transitland departures call is keyed by the onestop_id directly;
    exactly one resolve + one departures call occur per stop."""
    calls = []
    with patch("proxy._http_get_json", side_effect=_batch_http(capture=calls)):
        proxy.get_departures_by_onestop_ids(["s-test-10"], next_seconds=3600)

    resolve_calls = [c for c in calls if "stops" in c["url"] and "/departures" not in c["url"]]
    dep_calls = [c for c in calls if "/departures" in c["url"]]
    assert len(resolve_calls) == 1
    assert len(dep_calls) == 1
    assert "stops/s-test-10/departures" in dep_calls[0]["url"]


# ── Alert helpers ──────────────────────────────────────────────────────────────

_PAST = 1_000_000
_FUTURE = 9_999_999_999


def _active_alert(header="Delays", description="Some delays", severity="WARNING",
                  cause="CONSTRUCTION", effect="REDUCED_SERVICE", periods=None):
    return {
        "cause": cause,
        "effect": effect,
        "severity_level": severity,
        "header_text": [{"language": "en", "text": header}],
        "description_text": [{"language": "en", "text": description}],
        "tts_header_text": [],
        "tts_description_text": [],
        "url": [],
        "active_period": periods if periods is not None else [],
    }


def _departures_with_alerts(*alerts, on_parent=False, on_child=False):
    stop_alerts = [] if (on_parent or on_child) else list(alerts)
    parent_alerts = list(alerts) if on_parent else []
    child_alerts = list(alerts) if on_child else []
    return {
        "stops": [{
            "departures": [],
            "alerts": stop_alerts,
            "parent": {"alerts": parent_alerts} if on_parent else None,
            "children": [{"alerts": child_alerts}] if on_child else [],
        }]
    }


def test_get_departures_returns_alerts_key():
    with patch("proxy._http_get_json", side_effect=_make_http_mock(("/departures", DEPARTURES_RESPONSE))):
        result = proxy.get_departures(42, next_seconds=3600)
    assert "alerts" in result


def test_get_departures_returns_active_alert():
    data = _departures_with_alerts(_active_alert("Track work", periods=[{"start": _PAST, "end": _FUTURE}]))
    with patch("proxy._http_get_json", side_effect=_make_http_mock(("/departures", data))):
        result = proxy.get_departures(42, next_seconds=3600)
    assert len(result["alerts"]) == 1
    assert result["alerts"][0]["header_text"] == "Track work"
    assert result["alerts"][0]["severity_level"] == "WARNING"


def test_get_departures_filters_expired_alert():
    expired = _active_alert(periods=[{"start": _PAST, "end": _PAST}])
    data = _departures_with_alerts(expired)
    with patch("proxy._http_get_json", side_effect=_make_http_mock(("/departures", data))):
        result = proxy.get_departures(42, next_seconds=3600)
    assert result["alerts"] == []


def test_get_departures_filters_future_alert():
    future_only = _active_alert(periods=[{"start": _FUTURE, "end": _FUTURE}])
    data = _departures_with_alerts(future_only)
    with patch("proxy._http_get_json", side_effect=_make_http_mock(("/departures", data))):
        result = proxy.get_departures(42, next_seconds=3600)
    assert result["alerts"] == []


def test_get_departures_retains_alert_with_no_active_period():
    no_period = _active_alert(periods=[])
    data = _departures_with_alerts(no_period)
    with patch("proxy._http_get_json", side_effect=_make_http_mock(("/departures", data))):
        result = proxy.get_departures(42, next_seconds=3600)
    assert len(result["alerts"]) == 1


def test_get_departures_collects_alert_from_parent():
    data = _departures_with_alerts(_active_alert("Parent alert"), on_parent=True)
    with patch("proxy._http_get_json", side_effect=_make_http_mock(("/departures", data))):
        result = proxy.get_departures(42, next_seconds=3600)
    assert any(a["header_text"] == "Parent alert" for a in result["alerts"])


def test_get_departures_collects_alert_from_child():
    data = _departures_with_alerts(_active_alert("Child alert"), on_child=True)
    with patch("proxy._http_get_json", side_effect=_make_http_mock(("/departures", data))):
        result = proxy.get_departures(42, next_seconds=3600)
    assert any(a["header_text"] == "Child alert" for a in result["alerts"])


def test_get_departures_deduplicates_alerts_across_stop_and_parent():
    alert = _active_alert("Duplicate alert")
    data = {
        "stops": [{
            "departures": [],
            "alerts": [alert],
            "parent": {"alerts": [alert]},
            "children": [],
        }]
    }
    with patch("proxy._http_get_json", side_effect=_make_http_mock(("/departures", data))):
        result = proxy.get_departures(42, next_seconds=3600)
    assert len(result["alerts"]) == 1


def test_get_departures_prefers_english_translation():
    alert = {
        "cause": None, "effect": None, "severity_level": "INFO",
        "header_text": [
            {"language": "es", "text": "Retrasos"},
            {"language": "en", "text": "Delays"},
        ],
        "description_text": [],
        "tts_header_text": [], "tts_description_text": [], "url": [],
        "active_period": [],
    }
    data = _departures_with_alerts(alert)
    with patch("proxy._http_get_json", side_effect=_make_http_mock(("/departures", data))):
        result = proxy.get_departures(42, next_seconds=3600)
    assert result["alerts"][0]["header_text"] == "Delays"


def test_get_departures_falls_back_to_first_non_empty_translation():
    alert = {
        "cause": None, "effect": None, "severity_level": "INFO",
        "header_text": [{"language": "fr", "text": "Retards"}],
        "description_text": [],
        "tts_header_text": [], "tts_description_text": [], "url": [],
        "active_period": [],
    }
    data = _departures_with_alerts(alert)
    with patch("proxy._http_get_json", side_effect=_make_http_mock(("/departures", data))):
        result = proxy.get_departures(42, next_seconds=3600)
    assert result["alerts"][0]["header_text"] == "Retards"


def test_get_departures_by_onestop_ids_includes_alerts_per_stop():
    alert = _active_alert("Service change")
    data_with_alert = _departures_with_alerts(alert)
    with patch("proxy._http_get_json", side_effect=_batch_http({"s-test-10": data_with_alert, "s-test-20": DEPARTURES_RESPONSE})):
        result = proxy.get_departures_by_onestop_ids(["s-test-10", "s-test-20"], next_seconds=3600)

    stop10 = next(s for s in result["stops"] if s["onestop_id"] == "s-test-10")
    stop20 = next(s for s in result["stops"] if s["onestop_id"] == "s-test-20")
    assert len(stop10["alerts"]) == 1
    assert stop10["alerts"][0]["header_text"] == "Service change"
    assert stop20["alerts"] == []


def test_get_departures_by_onestop_ids_alerts_key_present_when_no_alerts():
    with patch("proxy._http_get_json", side_effect=_batch_http({"s-test-10": DEPARTURES_RESPONSE})):
        result = proxy.get_departures_by_onestop_ids(["s-test-10"], next_seconds=3600)
    assert "alerts" in result["stops"][0]
    assert result["stops"][0]["alerts"] == []


# ── geocode ────────────────────────────────────────────────────────────────────

NOMINATIM_RESPONSE = [
    {
        "place_id": 123456,
        "display_name": "Berkeley, Alameda County, California, United States",
        "lat": "37.8708393",
        "lon": "-122.272863",
        "category": "boundary",
        "type": "administrative",
        "importance": 0.8,
    }
]


def test_geocode_returns_shaped_places():
    with patch("proxy._http_get_json", side_effect=_make_http_mock(("nominatim", NOMINATIM_RESPONSE))):
        result = proxy.geocode("Berkeley CA")

    assert "places" in result
    place = result["places"][0]
    assert place["place_id"] == "123456"
    assert place["display_name"] == "Berkeley, Alameda County, California, United States"
    assert place["lat"] == pytest.approx(37.8708393)
    assert place["lon"] == pytest.approx(-122.272863)
    assert place["category"] == "boundary"
    assert place["type"] == "administrative"


def test_geocode_returns_empty_list_on_no_results():
    with patch("proxy._http_get_json", side_effect=_make_http_mock(("nominatim", []))):
        result = proxy.geocode("xyzzy-nonexistent-place-99999")

    assert result == {"places": []}


def test_geocode_limit_capped_at_10():
    calls = []
    with patch("proxy._http_get_json", side_effect=_make_http_mock(("nominatim", []), capture=calls)):
        proxy.geocode("Berkeley", limit=999)

    nom_call = next(c for c in calls if "nominatim" in c["url"])
    assert int(nom_call["params"].get("limit", 999)) <= 10


def test_geocode_uses_jsonv2_format():
    calls = []
    with patch("proxy._http_get_json", side_effect=_make_http_mock(("nominatim", []), capture=calls)):
        proxy.geocode("Berkeley")

    nom_call = next(c for c in calls if "nominatim" in c["url"])
    assert nom_call["params"].get("format") == "jsonv2"


def test_geocode_focus_viewbox_forwarded():
    calls = []
    with patch("proxy._http_get_json", side_effect=_make_http_mock(("nominatim", []), capture=calls)):
        proxy.geocode("station", focus_lat=37.8, focus_lon=-122.3)

    nom_call = next(c for c in calls if "nominatim" in c["url"])
    assert "viewbox" in nom_call["params"]
    assert nom_call["params"].get("bounded") in ("0", 0)


def test_geocode_no_viewbox_without_focus():
    calls = []
    with patch("proxy._http_get_json", side_effect=_make_http_mock(("nominatim", []), capture=calls)):
        proxy.geocode("station")

    nom_call = next(c for c in calls if "nominatim" in c["url"])
    assert "viewbox" not in nom_call["params"]


def test_geocode_accept_language_forwarded():
    calls = []
    with patch("proxy._http_get_json", side_effect=_make_http_mock(("nominatim", []), capture=calls)):
        proxy.geocode("station", accept_language="es")

    nom_call = next(c for c in calls if "nominatim" in c["url"])
    assert nom_call["headers"].get("Accept-Language") == "es"


def test_geocode_does_not_leak_upstream_key():
    with patch("proxy._http_get_json", side_effect=_make_http_mock(("nominatim", NOMINATIM_RESPONSE))):
        result = proxy.geocode("Berkeley")

    assert "real-upstream-key" not in json.dumps(result)


def test_geocode_upstream_500_raises_502():
    import bottle

    def raise_502(url, params=None, headers=None, timeout=None, allow_404=False):
        raise bottle.HTTPResponse(
            body='{"error":"upstream","status":500}', status=502,
            headers={"Content-Type": "application/json"},
        )

    with patch("proxy._http_get_json", side_effect=raise_502):
        with pytest.raises(bottle.HTTPResponse) as exc:
            proxy.geocode("Berkeley")
    assert exc.value.status_code == 502


def test_geocode_upstream_timeout_raises_504():
    import bottle

    def raise_504(url, params=None, headers=None, timeout=None, allow_404=False):
        raise bottle.HTTPResponse(
            body='{"error":"upstream_timeout"}', status=504,
            headers={"Content-Type": "application/json"},
        )

    with patch("proxy._http_get_json", side_effect=raise_504):
        with pytest.raises(bottle.HTTPResponse) as exc:
            proxy.geocode("Berkeley")
    assert exc.value.status_code == 504


def test_geocode_outbound_rate_limited_to_1rps(monkeypatch):
    proxy._nominatim_last_call = time.monotonic()
    sleeps = []
    monkeypatch.setattr(proxy.time, "sleep", lambda s: sleeps.append(s))
    with patch("proxy._http_get_json", side_effect=_make_http_mock(("nominatim", []))):
        proxy.geocode("Berkeley")
    assert any(s > 0 for s in sleeps), "expected throttle sleep when called within 1s of prior call"
