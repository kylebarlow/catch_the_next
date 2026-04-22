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
