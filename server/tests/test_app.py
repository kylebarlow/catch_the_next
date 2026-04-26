import os
os.environ.setdefault("TRANSITLAND_API_KEY", "real-upstream-key")
os.environ.setdefault("APP_API_KEYS", "test-key")
os.environ.setdefault("TRANSITLAND_BASE_URL", "http://mock-transitland")

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
    captured, body = _call(testapp, "/api/v2/rest/departures", query="stop_ids=abc,123")
    assert captured["status"].startswith("400")
    result = json.loads(body)
    assert result["error"] == "bad_request"
    assert "comma-separated integers" in result["detail"]


@patch("proxy._upstream_get")
def test_batch_departures_rejects_too_many_ids(mock_upstream):
    testapp = app
    ids = ",".join(str(i) for i in range(10))
    captured, body = _call(testapp, "/api/v2/rest/departures", query=f"stop_ids={ids}")
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
    captured, body = _call(testapp, "/api/v2/rest/departures", query="stop_ids=10,20")
    assert captured["status"].startswith("200")
    result = json.loads(body)
    assert "stops" in result
    assert len(result["stops"]) == 2
