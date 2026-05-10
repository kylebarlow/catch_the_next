import os
os.environ.setdefault("TRANSITLAND_API_KEY", "real-upstream-key")
os.environ.setdefault("APP_API_KEYS", "test-key")
os.environ.setdefault("TRANSITLAND_BASE_URL", "http://mock-transitland")
os.environ.setdefault("STATS_PATH_SECRET", "")

import json
import bottle
import pytest
from unittest.mock import patch
from app import app


@pytest.fixture
def testapp():
    bottle.debug(False)
    return app


def _call(testapp, path, query="", api_key="test-key"):
    environ = {
        "REQUEST_METHOD": "GET",
        "PATH_INFO": path,
        "QUERY_STRING": query,
        "CONTENT_TYPE": "application/json",
        "wsgi.input": b"",
        "wsgi.errors": __import__("io").StringIO(),
        "wsgi.url_scheme": "http",
        "SERVER_NAME": "localhost",
        "SERVER_PORT": "80",
        "HTTP_X_API_KEY": api_key or "",
    }
    captured = {}

    def start_response(status, headers, exc_info=None):
        captured["status"] = status
        captured["headers"] = dict(headers)

    body = testapp(environ, start_response)
    return captured, b"".join(body)


@patch("proxy._upstream_get")
def test_batch_departures_rejects_missing_stop_ids(mock_upstream):
    testapp = app
    captured, body = _call(testapp, "/api/v2/rest/departures", query="")
    assert captured["status"].startswith("400")
    result = json.loads(body)
    assert result["error"] == "bad_request"
    assert "stop_ids" in result["detail"]


@patch("proxy._upstream_get")
def test_batch_departures_rejects_malformed_ids(mock_upstream):
    testapp = app
    captured, body = _call(testapp, "/api/v2/rest/departures", query="onestop_ids=,,,")
    assert captured["status"].startswith("400")
    result = json.loads(body)
    assert result["error"] == "bad_request"
    assert "onestop_ids" in result["detail"]


@patch("proxy._upstream_get")
def test_batch_departures_rejects_too_many_ids(mock_upstream):
    testapp = app
    ids = ",".join(f"s-id{i}" for i in range(10))
    captured, body = _call(testapp, "/api/v2/rest/departures", query=f"onestop_ids={ids}")
    assert captured["status"].startswith("400")
    result = json.loads(body)
    assert result["error"] == "bad_request"
    assert "too many" in result["detail"]


@patch("proxy._upstream_get")
def test_batch_departures_accepts_valid_request(mock_upstream):
    mock_upstream.return_value = {
        "stops": [{"departures": [], "children": []}]
    }
    testapp = app
    captured, body = _call(testapp, "/api/v2/rest/departures", query="onestop_ids=s-abc,s-def")
    assert captured["status"].startswith("200")
    result = json.loads(body)
    assert "stops" in result
    assert len(result["stops"]) == 2


# ── /api/v2/rest/geocode ───────────────────────────────────────────────────────

@patch("app.geocode")
def test_geocode_route_registered(mock_geocode):
    mock_geocode.return_value = {"places": []}
    captured, body = _call(app, "/api/v2/rest/geocode", query="q=Berkeley")
    assert captured["status"].startswith("200")
    result = json.loads(body)
    assert "places" in result


@patch("app.geocode")
def test_geocode_requires_auth(mock_geocode):
    mock_geocode.return_value = {"places": []}
    captured, body = _call(app, "/api/v2/rest/geocode", query="q=Berkeley", api_key=None)
    assert captured["status"].startswith("401")


@patch("app.geocode")
def test_geocode_400_missing_q(mock_geocode):
    captured, body = _call(app, "/api/v2/rest/geocode", query="")
    assert captured["status"].startswith("400")
    result = json.loads(body)
    assert result["error"] == "bad_request"
    assert "q" in result["detail"]


@patch("app.geocode")
def test_geocode_400_whitespace_only_q(mock_geocode):
    captured, body = _call(app, "/api/v2/rest/geocode", query="q=   ")
    assert captured["status"].startswith("400")
    result = json.loads(body)
    assert result["error"] == "bad_request"


@patch("app.geocode")
def test_geocode_400_partial_focus_lat_only(mock_geocode):
    captured, body = _call(app, "/api/v2/rest/geocode", query="q=Berkeley&focus_lat=37.8")
    assert captured["status"].startswith("400")
    result = json.loads(body)
    assert result["error"] == "bad_request"


@patch("app.geocode")
def test_geocode_400_partial_focus_lon_only(mock_geocode):
    captured, body = _call(app, "/api/v2/rest/geocode", query="q=Berkeley&focus_lon=-122.3")
    assert captured["status"].startswith("400")
    result = json.loads(body)
    assert result["error"] == "bad_request"


@patch("app.geocode")
def test_geocode_400_non_numeric_focus(mock_geocode):
    captured, body = _call(app, "/api/v2/rest/geocode",
                           query="q=Berkeley&focus_lat=abc&focus_lon=-122.3")
    assert captured["status"].startswith("400")
    result = json.loads(body)
    assert result["error"] == "bad_request"


@patch("app.geocode")
def test_geocode_passes_focus_to_proxy(mock_geocode):
    mock_geocode.return_value = {"places": []}
    _call(app, "/api/v2/rest/geocode", query="q=Berkeley&focus_lat=37.8&focus_lon=-122.3")
    mock_geocode.assert_called_once()
    args = mock_geocode.call_args.args  # (q, focus_lat, focus_lon, limit, accept_language)
    assert args[1] == pytest.approx(37.8)
    assert args[2] == pytest.approx(-122.3)


@patch("app.geocode")
def test_geocode_400_q_too_long(mock_geocode):
    long_q = "a" * 201
    captured, body = _call(app, "/api/v2/rest/geocode", query=f"q={long_q}")
    assert captured["status"].startswith("400")
    result = json.loads(body)
    assert result["error"] == "bad_request"


# ── /stats routes ─────────────────────────────────────────────────────────────

_FAKE_STATS = {
    "windows": {
        "hour": {"inbound_total": 1, "unique_ips": 1, "transitland_calls": 1,
                 "nominatim_calls": 0, "error_count": 0, "by_endpoint": {}, "top_ips": []},
        "day":  {"inbound_total": 1, "unique_ips": 1, "transitland_calls": 1,
                 "nominatim_calls": 0, "error_count": 0, "by_endpoint": {}, "top_ips": []},
        "week": {"inbound_total": 1, "unique_ips": 1, "transitland_calls": 1,
                 "nominatim_calls": 0, "error_count": 0, "by_endpoint": {}, "top_ips": []},
        "all":  {"inbound_total": 1, "unique_ips": 1, "transitland_calls": 1,
                 "nominatim_calls": 0, "error_count": 0, "by_endpoint": {}, "top_ips": []},
    },
    "log_path": "/dev/null",
    "log_size_bytes": 0,
    "oldest_entry": None,
    "newest_entry": None,
    "generated_at": "2026-05-10T12:00:00+00:00",
}


@patch("app._stats_secret", "test-secret")
@patch("app.load_stats", return_value=_FAKE_STATS)
def test_stats_json_200(mock_load_stats):
    captured, body = _call(app, "/_internal/test-secret/stats.json", api_key=None)
    assert captured["status"].startswith("200")
    result = json.loads(body)
    assert "windows" in result
    assert set(result["windows"].keys()) == {"hour", "day", "week", "all"}


@patch("app._stats_secret", "test-secret")
@patch("app.load_stats", return_value=_FAKE_STATS)
def test_stats_html_200(mock_load_stats):
    captured, body = _call(app, "/_internal/test-secret/stats", api_key=None)
    assert captured["status"].startswith("200")
    assert "text/html" in captured["headers"].get("Content-Type", "")
    assert b"CTN Proxy Stats" in body


@patch("app._stats_secret", "test-secret")
def test_stats_json_404_wrong_token():
    captured, body = _call(app, "/_internal/wrongtoken/stats.json", api_key=None)
    assert captured["status"].startswith("404")


@patch("app._stats_secret", "")
def test_stats_json_404_no_secret():
    captured, body = _call(app, "/_internal/anything/stats.json", api_key=None)
    assert captured["status"].startswith("404")
