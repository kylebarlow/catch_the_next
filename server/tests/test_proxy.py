import os
os.environ.setdefault("TRANSITLAND_API_KEY", "real-upstream-key")
os.environ.setdefault("APP_API_KEYS", "app-key")
os.environ.setdefault("TRANSITLAND_BASE_URL", "http://mock-transitland")

import json
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

    assert int(sent_params["radius"]) <= 2000


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
    minutes = [d["departure_minutes"] for d in deps]
    assert minutes == sorted(minutes)


def test_upstream_500_raises_http_response():
    import bottle
    with req_mock.Mocker() as m:
        m.get("http://mock-transitland/stops", status_code=500)
        with pytest.raises(bottle.HTTPResponse) as exc:
            proxy.get_stops(37.8, -122.4)
    assert exc.value.status_code == 502
