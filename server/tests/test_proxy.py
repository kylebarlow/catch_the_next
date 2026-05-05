import os
os.environ.setdefault("TRANSITLAND_API_KEY", "real-upstream-key")
os.environ.setdefault("APP_API_KEYS", "app-key")
os.environ.setdefault("TRANSITLAND_BASE_URL", "http://mock-transitland")

import json
import time
import pytest
import requests_mock as req_mock
import proxy


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


def test_get_stops_returns_shaped_data():
    with req_mock.Mocker() as m:
        m.get("http://mock-transitland/stops", json=STOPS_RESPONSE)
        result = proxy.get_stops(37.8, -122.4)

    assert "stops" in result
    assert result["stops"][0]["stop_name"] == "Test Stop"
    assert result["stops"][0]["lat"] == 37.8
    assert result["stops"][0]["lon"] == -122.4


def test_get_stops_injects_upstream_key():
    with req_mock.Mocker() as m:
        m.get("http://mock-transitland/stops", json=STOPS_RESPONSE)
        proxy.get_stops(37.8, -122.4)
        assert "apikey=" in m.last_request.url


def test_get_stops_does_not_leak_key_in_response():
    with req_mock.Mocker() as m:
        m.get("http://mock-transitland/stops", json=STOPS_RESPONSE)
        result = proxy.get_stops(37.8, -122.4)

    assert "real-upstream-key" not in json.dumps(result)


def test_get_stops_clamps_radius():
    with req_mock.Mocker() as m:
        m.get("http://mock-transitland/stops", json=STOPS_RESPONSE)
        proxy.get_stops(37.8, -122.4, radius=99999)
        sent_params = dict(pair.split("=") for pair in
                           m.last_request.url.split("?")[1].split("&"))

    assert int(sent_params["radius"]) <= 5000


# Regression: at Duboce Park (37.76955, -122.4332) the upstream API sorts stops
# alphabetically. With a low limit, alphabetically-early but distant bus stops
# crowd out the actually-closest N-train stops ("Duboce St/Noe St/Duboce Park",
# "Sunset Tunnel East Portal"). The proxy must sort returned stops by distance
# from the query point and return the closest `limit` stops.
DUBOCE_PARK_LIKE_RESPONSE = {
    "stops": [
        # Far (~400m) but alphabetically first — would dominate a small limit.
        {"id": 1, "stop_id": "A1", "stop_name": "14th St & Castro St",
         "geometry": {"coordinates": [-122.4350, 37.7660]}},
        {"id": 2, "stop_id": "A2", "stop_name": "14th St & Church St",
         "geometry": {"coordinates": [-122.4291, 37.7660]}},
        {"id": 3, "stop_id": "A3", "stop_name": "Castro St & Duboce Ave",
         "geometry": {"coordinates": [-122.4350, 37.7672]}},
        # Close (~30m) but alphabetically late — the N-train stops the user wants.
        {"id": 100, "stop_id": "N1", "stop_name": "Duboce St/Noe St/Duboce Park",
         "geometry": {"coordinates": [-122.43356, 37.76936]}},
        {"id": 101, "stop_id": "N2", "stop_name": "Sunset Tunnel East Portal",
         "geometry": {"coordinates": [-122.43351, 37.76929]}},
    ]
}


def test_get_stops_sorts_by_distance_not_alphabetical():
    with req_mock.Mocker() as m:
        m.get("http://mock-transitland/stops", json=DUBOCE_PARK_LIKE_RESPONSE)
        result = proxy.get_stops(37.76955, -122.43320, radius=600, limit=20)

    names = [s["stop_name"] for s in result["stops"]]
    # The N-train stops are physically closest and must come first, even though
    # their names sort alphabetically after the bus stops.
    assert names[0] in {"Duboce St/Noe St/Duboce Park", "Sunset Tunnel East Portal"}
    assert names[1] in {"Duboce St/Noe St/Duboce Park", "Sunset Tunnel East Portal"}


def test_get_stops_low_limit_keeps_closest_not_alphabetical():
    with req_mock.Mocker() as m:
        m.get("http://mock-transitland/stops", json=DUBOCE_PARK_LIKE_RESPONSE)
        result = proxy.get_stops(37.76955, -122.43320, radius=600, limit=2)

    names = {s["stop_name"] for s in result["stops"]}
    assert names == {"Duboce St/Noe St/Duboce Park", "Sunset Tunnel East Portal"}


def test_get_stops_requests_wide_candidate_set_upstream():
    # The proxy should always request a wide candidate set from upstream so that
    # alphabetical truncation cannot drop the closest stops, regardless of the
    # caller-requested limit.
    with req_mock.Mocker() as m:
        m.get("http://mock-transitland/stops", json=STOPS_RESPONSE)
        proxy.get_stops(37.8, -122.4, limit=5)
        sent_params = dict(pair.split("=") for pair in
                           m.last_request.url.split("?")[1].split("&"))

    assert int(sent_params["limit"]) >= 100


def test_get_stops_skips_entries_missing_geometry():
    response = {
        "stops": [
            {"id": 1, "stop_id": "A", "stop_name": "Has Geom",
             "geometry": {"coordinates": [-122.4, 37.8]}},
            {"id": 2, "stop_id": "B", "stop_name": "No Geom",
             "geometry": {"coordinates": [None, None]}},
        ]
    }
    with req_mock.Mocker() as m:
        m.get("http://mock-transitland/stops", json=response)
        result = proxy.get_stops(37.8, -122.4)

    assert [s["stop_name"] for s in result["stops"]] == ["Has Geom"]


def test_get_stops_does_not_leak_internal_distance_field():
    with req_mock.Mocker() as m:
        m.get("http://mock-transitland/stops", json=STOPS_RESPONSE)
        result = proxy.get_stops(37.8, -122.4)

    assert all("_dist_m" not in s for s in result["stops"])


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
    with req_mock.Mocker() as m:
        m.get("http://mock-transitland/stops/42/departures", json=DEPARTURES_RESPONSE_NULL_FIELDS)
        result = proxy.get_departures(42, next_seconds=3600)
    assert "departures" in result


def test_get_departures_returns_sorted():
    with req_mock.Mocker() as m:
        m.get("http://mock-transitland/stops/42/departures", json=DEPARTURES_RESPONSE)
        result = proxy.get_departures(42, next_seconds=3600)

    assert "departures" in result
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
    with req_mock.Mocker() as m:
        m.get("http://mock-transitland/stops/42/departures", json=DEPARTURES_RESPONSE_WITH_ESTIMATE)
        result = proxy.get_departures(42, next_seconds=3600)

    dep = result["departures"][0]
    assert dep["time_source"] == "LIVE"
    assert dep["live_departure_utc"] == "2099-01-01T12:03:00Z"
    assert dep["live_departure_time"] == "12:03"
    assert dep["scheduled_departure_utc"] == "2099-01-01T12:00:00Z"
    assert dep["scheduled_departure_minutes"] is not None
    assert dep["live_departure_minutes"] is not None


def test_departure_without_estimate_is_scheduled():
    with req_mock.Mocker() as m:
        m.get("http://mock-transitland/stops/42/departures", json=DEPARTURES_RESPONSE_SCHEDULED_ONLY)
        result = proxy.get_departures(42, next_seconds=3600)

    dep = result["departures"][0]
    assert dep["time_source"] == "SCHEDULED"
    assert dep["live_departure_utc"] is None
    assert dep["live_departure_minutes"] is None
    assert dep["scheduled_departure_utc"] == "2099-01-01T12:00:00Z"


def test_static_without_estimate_is_scheduled():
    with req_mock.Mocker() as m:
        m.get("http://mock-transitland/stops/42/departures", json=DEPARTURES_RESPONSE_STATIC_NO_ESTIMATE)
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
    with req_mock.Mocker() as m:
        m.get("http://mock-transitland/stops/42/departures", json=response)
        result = proxy.get_departures(42, next_seconds=3600)

    deps = result["departures"]
    minutes = []
    for d in deps:
        if d["time_source"] == "LIVE" and d["live_departure_minutes"] is not None:
            minutes.append(d["live_departure_minutes"])
        else:
            minutes.append(d["scheduled_departure_minutes"])
    assert minutes == sorted(minutes)


def test_gtfs_fallback_still_works_without_utc():
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
    with req_mock.Mocker() as m:
        m.get("http://mock-transitland/stops/42/departures", json=response)
        result = proxy.get_departures(42, next_seconds=86400)

    deps = result["departures"]
    for d in deps:
        assert "time_source" in d
        assert "scheduled_departure_time" in d


def test_upstream_500_raises_http_response():
    import bottle
    with req_mock.Mocker() as m:
        m.get("http://mock-transitland/stops", status_code=500)
        with pytest.raises(bottle.HTTPResponse) as exc:
            proxy.get_stops(37.8, -122.4)
    assert exc.value.status_code == 502


def test_get_stops_passes_through_feed_attribution():
    with req_mock.Mocker() as m:
        m.get("http://mock-transitland/stops", json=STOPS_RESPONSE_WITH_FEED)
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
    with req_mock.Mocker() as m:
        m.get("http://mock-transitland/stops", json=STOPS_RESPONSE)
        result = proxy.get_stops(37.8, -122.4)

    stop = result["stops"][0]
    assert stop["feed_onestop_id"] is None
    assert stop["feed_name"] is None
    assert stop["attribution_text"] is None


def test_get_departures_passes_through_agency_and_feed():
    with req_mock.Mocker() as m:
        m.get("http://mock-transitland/stops/42/departures", json=DEPARTURES_RESPONSE_WITH_AGENCY)
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
    with req_mock.Mocker() as m:
        m.get("http://mock-transitland/stops/42/departures", json=DEPARTURES_RESPONSE)
        result = proxy.get_departures(42, next_seconds=3600)

    dep = result["departures"][0]
    assert dep["agency_name"] is None
    assert dep["feed_onestop_id"] is None
    assert dep["attribution_text"] is None


def test_proxy_uses_descriptive_user_agent():
    with req_mock.Mocker() as m:
        m.get("http://mock-transitland/stops", json=STOPS_RESPONSE)
        proxy.get_stops(37.8, -122.4)
        ua = m.last_request.headers.get("User-Agent", "")

    assert "CatchTheNext" in ua, f"Expected CatchTheNext in User-Agent, got: {ua}"


def test_get_departures_batch_returns_grouped_results():
    with req_mock.Mocker() as m:
        m.get("http://mock-transitland/stops/10/departures", json=DEPARTURES_RESPONSE)
        m.get("http://mock-transitland/stops/20/departures", json=DEPARTURES_RESPONSE_WITH_AGENCY)
        result = proxy.get_departures_batch([10, 20], next_seconds=3600)

    assert "stops" in result
    assert len(result["stops"]) == 2
    assert result["stops"][0]["stop_id"] == 10
    assert result["stops"][1]["stop_id"] == 20
    assert "departures" in result["stops"][0]
    assert "departures" in result["stops"][1]
    assert len(result["stops"][0]["departures"]) > 0
    assert len(result["stops"][1]["departures"]) > 0


def test_get_departures_batch_preserves_request_order():
    with req_mock.Mocker() as m:
        m.get("http://mock-transitland/stops/30/departures", json=DEPARTURES_RESPONSE)
        m.get("http://mock-transitland/stops/10/departures", json=DEPARTURES_RESPONSE)
        result = proxy.get_departures_batch([30, 10], next_seconds=3600)

    assert result["stops"][0]["stop_id"] == 30
    assert result["stops"][1]["stop_id"] == 10


def test_get_departures_batch_returns_empty_departures_for_stop_with_none():
    response_no_deps = {"stops": [{"departures": None, "children": None}]}
    with req_mock.Mocker() as m:
        m.get("http://mock-transitland/stops/10/departures", json=DEPARTURES_RESPONSE)
        m.get("http://mock-transitland/stops/20/departures", json=response_no_deps)
        result = proxy.get_departures_batch([10, 20], next_seconds=3600)

    assert result["stops"][0]["stop_id"] == 10
    assert len(result["stops"][0]["departures"]) > 0
    assert result["stops"][1]["stop_id"] == 20
    assert result["stops"][1]["departures"] == []


def test_get_departures_batch_applies_next_to_all_calls():
    with req_mock.Mocker() as m:
        m.get("http://mock-transitland/stops/10/departures", json=DEPARTURES_RESPONSE)
        m.get("http://mock-transitland/stops/20/departures", json=DEPARTURES_RESPONSE)
        proxy.get_departures_batch([10, 20], next_seconds=1800)

    for req in m.request_history:
        assert "next=1800" in req.url


def test_get_departures_batch_shaping_matches_single_stop():
    with req_mock.Mocker() as m:
        m.get("http://mock-transitland/stops/42/departures", json=DEPARTURES_RESPONSE_WITH_AGENCY)
        single = proxy.get_departures(42, next_seconds=3600)
    with req_mock.Mocker() as m:
        m.get("http://mock-transitland/stops/42/departures", json=DEPARTURES_RESPONSE_WITH_AGENCY)
        batch = proxy.get_departures_batch([42], next_seconds=3600)

    single_deps = single["departures"]
    batch_deps = batch["stops"][0]["departures"]
    assert single_deps == batch_deps


def test_shape_departures_is_used_by_get_departures():
    with req_mock.Mocker() as m:
        m.get("http://mock-transitland/stops/42/departures", json=DEPARTURES_RESPONSE_WITH_ESTIMATE)
        result = proxy.get_departures(42, next_seconds=3600)

    dep = result["departures"][0]
    assert dep["time_source"] == "LIVE"
    assert dep["live_departure_utc"] == "2099-01-01T12:03:00Z"


# ── Alert helpers ──────────────────────────────────────────────────────────────

_PAST = 1_000_000      # well before "now" in tests
_FUTURE = 9_999_999_999


def _active_alert(header="Delays", description="Some delays", severity="WARNING",
                  cause="CONSTRUCTION", effect="REDUCED_SERVICE", periods=None):
    """Build a minimal Transitland alert dict that is currently active."""
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
    """Build a DEPARTURES-style response dict with alerts placed on stop/parent/child."""
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


# ── include_alerts forwarded ───────────────────────────────────────────────────

def test_get_departures_sends_include_alerts():
    with req_mock.Mocker() as m:
        m.get("http://mock-transitland/stops/42/departures", json=DEPARTURES_RESPONSE)
        proxy.get_departures(42, next_seconds=3600)
        assert "include_alerts=true" in m.last_request.url


def test_get_departures_batch_sends_include_alerts():
    with req_mock.Mocker() as m:
        m.get("http://mock-transitland/stops/10/departures", json=DEPARTURES_RESPONSE)
        proxy.get_departures_batch([10], next_seconds=3600)
        assert "include_alerts=true" in m.last_request.url


# ── alert shaping ──────────────────────────────────────────────────────────────

def test_get_departures_returns_alerts_key():
    with req_mock.Mocker() as m:
        m.get("http://mock-transitland/stops/42/departures", json=DEPARTURES_RESPONSE)
        result = proxy.get_departures(42, next_seconds=3600)
    assert "alerts" in result


def test_get_departures_returns_active_alert():
    data = _departures_with_alerts(_active_alert("Track work", periods=[{"start": _PAST, "end": _FUTURE}]))
    with req_mock.Mocker() as m:
        m.get("http://mock-transitland/stops/42/departures", json=data)
        result = proxy.get_departures(42, next_seconds=3600)
    assert len(result["alerts"]) == 1
    assert result["alerts"][0]["header_text"] == "Track work"
    assert result["alerts"][0]["severity_level"] == "WARNING"


def test_get_departures_filters_expired_alert():
    expired = _active_alert(periods=[{"start": _PAST, "end": _PAST}])
    data = _departures_with_alerts(expired)
    with req_mock.Mocker() as m:
        m.get("http://mock-transitland/stops/42/departures", json=data)
        result = proxy.get_departures(42, next_seconds=3600)
    assert result["alerts"] == []


def test_get_departures_filters_future_alert():
    future_only = _active_alert(periods=[{"start": _FUTURE, "end": _FUTURE}])
    data = _departures_with_alerts(future_only)
    with req_mock.Mocker() as m:
        m.get("http://mock-transitland/stops/42/departures", json=data)
        result = proxy.get_departures(42, next_seconds=3600)
    assert result["alerts"] == []


def test_get_departures_retains_alert_with_no_active_period():
    no_period = _active_alert(periods=[])
    data = _departures_with_alerts(no_period)
    with req_mock.Mocker() as m:
        m.get("http://mock-transitland/stops/42/departures", json=data)
        result = proxy.get_departures(42, next_seconds=3600)
    assert len(result["alerts"]) == 1


def test_get_departures_collects_alert_from_parent():
    data = _departures_with_alerts(_active_alert("Parent alert"), on_parent=True)
    with req_mock.Mocker() as m:
        m.get("http://mock-transitland/stops/42/departures", json=data)
        result = proxy.get_departures(42, next_seconds=3600)
    assert any(a["header_text"] == "Parent alert" for a in result["alerts"])


def test_get_departures_collects_alert_from_child():
    data = _departures_with_alerts(_active_alert("Child alert"), on_child=True)
    with req_mock.Mocker() as m:
        m.get("http://mock-transitland/stops/42/departures", json=data)
        result = proxy.get_departures(42, next_seconds=3600)
    assert any(a["header_text"] == "Child alert" for a in result["alerts"])


def test_get_departures_deduplicates_alerts_across_stop_and_parent():
    alert = _active_alert("Duplicate alert")
    # Same alert on both stop and parent — should only appear once.
    data = {
        "stops": [{
            "departures": [],
            "alerts": [alert],
            "parent": {"alerts": [alert]},
            "children": [],
        }]
    }
    with req_mock.Mocker() as m:
        m.get("http://mock-transitland/stops/42/departures", json=data)
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
    with req_mock.Mocker() as m:
        m.get("http://mock-transitland/stops/42/departures", json=data)
        result = proxy.get_departures(42, next_seconds=3600)
    assert result["alerts"][0]["header_text"] == "Delays"


def test_get_departures_falls_back_to_first_non_empty_translation():
    alert = {
        "cause": None, "effect": None, "severity_level": "INFO",
        "header_text": [
            {"language": "fr", "text": "Retards"},
        ],
        "description_text": [],
        "tts_header_text": [], "tts_description_text": [], "url": [],
        "active_period": [],
    }
    data = _departures_with_alerts(alert)
    with req_mock.Mocker() as m:
        m.get("http://mock-transitland/stops/42/departures", json=data)
        result = proxy.get_departures(42, next_seconds=3600)
    assert result["alerts"][0]["header_text"] == "Retards"


# ── batch alert passthrough ────────────────────────────────────────────────────

def test_get_departures_batch_includes_alerts_per_stop():
    alert = _active_alert("Service change")
    data_with_alert = _departures_with_alerts(alert)
    with req_mock.Mocker() as m:
        m.get("http://mock-transitland/stops/10/departures", json=data_with_alert)
        m.get("http://mock-transitland/stops/20/departures", json=DEPARTURES_RESPONSE)
        result = proxy.get_departures_batch([10, 20], next_seconds=3600)

    stop10 = next(s for s in result["stops"] if s["stop_id"] == 10)
    stop20 = next(s for s in result["stops"] if s["stop_id"] == 20)
    assert len(stop10["alerts"]) == 1
    assert stop10["alerts"][0]["header_text"] == "Service change"
    assert stop20["alerts"] == []


def test_get_departures_batch_alerts_key_present_when_no_alerts():
    with req_mock.Mocker() as m:
        m.get("http://mock-transitland/stops/10/departures", json=DEPARTURES_RESPONSE)
        result = proxy.get_departures_batch([10], next_seconds=3600)
    assert "alerts" in result["stops"][0]
    assert result["stops"][0]["alerts"] == []


# ── geocode ────────────────────────────────────────────────────────────────────

_NOMINATIM_URL = f"{proxy._NOMINATIM_BASE_URL}/search"

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
    with req_mock.Mocker() as m:
        m.get(_NOMINATIM_URL, json=NOMINATIM_RESPONSE)
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
    with req_mock.Mocker() as m:
        m.get(_NOMINATIM_URL, json=[])
        result = proxy.geocode("xyzzy-nonexistent-place-99999")

    assert result == {"places": []}


def test_geocode_limit_capped_at_10():
    with req_mock.Mocker() as m:
        m.get(_NOMINATIM_URL, json=[])
        proxy.geocode("Berkeley", limit=999)
        sent_url = m.last_request.url

    assert "limit=10" in sent_url


def test_geocode_uses_jsonv2_format():
    with req_mock.Mocker() as m:
        m.get(_NOMINATIM_URL, json=[])
        proxy.geocode("Berkeley")
        sent_url = m.last_request.url

    assert "format=jsonv2" in sent_url


def test_geocode_focus_viewbox_forwarded():
    with req_mock.Mocker() as m:
        m.get(_NOMINATIM_URL, json=[])
        proxy.geocode("station", focus_lat=37.8, focus_lon=-122.3)
        sent_url = m.last_request.url

    assert "viewbox=" in sent_url
    assert "bounded=0" in sent_url


def test_geocode_no_viewbox_without_focus():
    with req_mock.Mocker() as m:
        m.get(_NOMINATIM_URL, json=[])
        proxy.geocode("station")
        sent_url = m.last_request.url

    assert "viewbox" not in sent_url


def test_geocode_accept_language_forwarded():
    with req_mock.Mocker() as m:
        m.get(_NOMINATIM_URL, json=[])
        proxy.geocode("station", accept_language="es")
        sent_headers = m.last_request.headers

    assert sent_headers.get("Accept-Language") == "es"


def test_geocode_unique_user_agent_sent():
    with req_mock.Mocker() as m:
        m.get(_NOMINATIM_URL, json=[])
        proxy.geocode("Berkeley")
        ua = m.last_request.headers.get("User-Agent", "")

    assert "CatchTheNext" in ua


def test_geocode_upstream_500_raises_502():
    import bottle
    with req_mock.Mocker() as m:
        m.get(_NOMINATIM_URL, status_code=500)
        with pytest.raises(bottle.HTTPResponse) as exc:
            proxy.geocode("Berkeley")
    assert exc.value.status_code == 502


def test_geocode_upstream_timeout_raises_504():
    import bottle
    import requests.exceptions
    with req_mock.Mocker() as m:
        m.get(_NOMINATIM_URL, exc=requests.exceptions.Timeout)
        with pytest.raises(bottle.HTTPResponse) as exc:
            proxy.geocode("Berkeley")
    assert exc.value.status_code == 504


def test_geocode_does_not_leak_upstream_key():
    with req_mock.Mocker() as m:
        m.get(_NOMINATIM_URL, json=NOMINATIM_RESPONSE)
        result = proxy.geocode("Berkeley")

    assert "real-upstream-key" not in json.dumps(result)


def test_geocode_outbound_rate_limited_to_1rps(monkeypatch):
    proxy._nominatim_last_call = time.monotonic()
    sleeps = []
    monkeypatch.setattr(time, "sleep", lambda s: sleeps.append(s))
    with req_mock.Mocker() as m:
        m.get(_NOMINATIM_URL, json=[])
        proxy.geocode("Berkeley")
    assert any(s > 0 for s in sleeps), "expected throttle sleep when called within 1 s of prior call"


def test_get_stops_radius_capped_at_5000():
    with req_mock.Mocker() as m:
        m.get("http://mock-transitland/stops", json=STOPS_RESPONSE)
        proxy.get_stops(37.8, -122.4, radius=99999)
        sent_params = dict(pair.split("=") for pair in
                           m.last_request.url.split("?")[1].split("&"))

    assert int(sent_params["radius"]) <= 5000
