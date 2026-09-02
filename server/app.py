import hmac
import json
import bottle
from auth import require_auth, current_scope
from rate_limit import require_rate_limit
from proxy import get_stops, get_departures, get_departures_by_onestop_ids, geocode, _BATCH_MAX_STOPS
from config import load_config
from validation import (
    validated,
    optional_int,
    require_str,
    require_float_pair,
    optional_float_pair,
    require_csv,
)

_cfg = load_config()
_stats_secret = _cfg["STATS_PATH_SECRET"]

app = bottle.Bottle()


@app.route("/healthz")
def healthz():
    # Report the protobuf backend: the pure-Python fallback parses the 511
    # regional feed ~200x slower, and it can reappear silently after a
    # redeploy or Python upgrade (see deploy/push.sh --no-binary).
    from gtfs511.rt_db import protobuf_implementation
    bottle.response.content_type = "application/json"
    return json.dumps({"status": "ok", "protobuf": protobuf_implementation()})


@app.route("/api/v2/rest/stops")
@require_auth
@require_rate_limit
@validated
def stops():
    lat, lon = require_float_pair("lat", "lon")
    radius = optional_int("radius", 500)
    limit = optional_int("limit", 20)
    bottle.response.content_type = "application/json"
    return json.dumps(get_stops(lat, lon, radius, limit, scope=current_scope()))


@app.route("/api/v2/rest/geocode")
@require_auth
@require_rate_limit
@validated
def geocode_route():
    q = require_str("q", max_len=200)
    focus_lat, focus_lon = optional_float_pair("focus_lat", "focus_lon")
    limit = optional_int("limit", 10)
    accept_language = bottle.request.environ.get("HTTP_ACCEPT_LANGUAGE")
    bottle.response.content_type = "application/json"
    return json.dumps(geocode(q, focus_lat, focus_lon, limit, accept_language))


@app.route("/api/v2/rest/stops/<stop_id:int>/departures")
@require_auth
@require_rate_limit
@validated
def departures(stop_id):
    next_seconds = optional_int("next", 3600)
    bottle.response.content_type = "application/json"
    return json.dumps(get_departures(stop_id, next_seconds, scope=current_scope()))


@app.route("/api/v2/rest/departures")
@require_auth
@require_rate_limit
@validated
def departures_batch():
    onestop_ids = require_csv("onestop_ids", _BATCH_MAX_STOPS, "too many onestop_ids")
    next_seconds = optional_int("next", 3600)
    bottle.response.content_type = "application/json"
    return json.dumps(get_departures_by_onestop_ids(onestop_ids, next_seconds, scope=current_scope()))


def _stats_token_from_request():
    """Pull the stats token from a request header, never the URL path.

    A secret in the path is written verbatim into the Apache access log (the
    same log this app reads back), browser history, and any intermediary —
    effectively logging the token in plaintext. Headers are not logged, so we
    accept the token via X-Stats-Token or `Authorization: Bearer <token>`.
    """
    header = bottle.request.headers.get("X-Stats-Token")
    if header:
        return header
    auth = bottle.request.headers.get("Authorization", "")
    if auth.startswith("Bearer "):
        return auth[len("Bearer "):]
    return ""


def _stats_check():
    """Return True if the request carries the configured stats secret."""
    token = _stats_token_from_request()
    return bool(_stats_secret) and hmac.compare_digest(token, _stats_secret)


@app.route("/_internal/stats.json")
def stats_json():
    if not _stats_check():
        raise bottle.HTTPResponse(status=404, body="Not Found")
    from stats import load_stats
    bottle.response.content_type = "application/json"
    return json.dumps(load_stats())


@app.route("/_internal/stats")
def stats_html():
    if not _stats_check():
        raise bottle.HTTPResponse(status=404, body="Not Found")
    from stats import load_stats, render_html
    bottle.response.content_type = "text/html; charset=utf-8"
    return render_html(load_stats())
