import hmac
import json
import bottle
from auth import require_auth
from rate_limit import require_rate_limit
from proxy import get_stops, get_departures, get_departures_by_onestop_ids, geocode, _BATCH_MAX_STOPS
from config import load_config

_cfg = load_config()
_stats_secret = _cfg["STATS_PATH_SECRET"]

app = bottle.Bottle()


def _int_param(name, default):
    """Read a non-negative integer query param, or raise a clean 400.

    `lat`/`lon`/`focus_*` are already validated and return 400s; this gives the
    numeric siblings (`next`, `radius`, `limit`) the same treatment instead of
    letting `int("abc")` bubble up as an unhandled 500. Upstream caps still apply
    in proxy.py; this only rejects non-numeric/negative input.
    """
    raw = bottle.request.query.get(name)
    if raw is None or raw == "":
        return default
    try:
        value = int(raw)
    except (ValueError, TypeError):
        raise bottle.HTTPResponse(
            body=json.dumps({"error": "bad_request", "detail": f"{name} must be an integer"}),
            status=400, headers={"Content-Type": "application/json"},
        )
    if value < 0:
        raise bottle.HTTPResponse(
            body=json.dumps({"error": "bad_request", "detail": f"{name} must be non-negative"}),
            status=400, headers={"Content-Type": "application/json"},
        )
    return value


@app.route("/healthz")
def healthz():
    return "ok"


@app.route("/api/v2/rest/stops")
@require_auth
@require_rate_limit
def stops():
    lat = bottle.request.query.get("lat")
    lon = bottle.request.query.get("lon")
    if lat is None or lon is None:
        raise bottle.HTTPResponse(
            body='{"error":"bad_request","detail":"lat and lon are required"}',
            status=400, headers={"Content-Type": "application/json"},
        )
    try:
        lat, lon = float(lat), float(lon)
    except ValueError:
        raise bottle.HTTPResponse(
            body='{"error":"bad_request","detail":"lat and lon must be numeric"}',
            status=400, headers={"Content-Type": "application/json"},
        )

    radius = _int_param("radius", 500)
    limit = _int_param("limit", 20)
    bottle.response.content_type = "application/json"
    return json.dumps(get_stops(lat, lon, radius, limit))


@app.route("/api/v2/rest/geocode")
@require_auth
@require_rate_limit
def geocode_route():
    q = bottle.request.query.get("q", "").strip()
    if not q:
        raise bottle.HTTPResponse(
            body='{"error":"bad_request","detail":"q is required"}',
            status=400, headers={"Content-Type": "application/json"},
        )
    if len(q) > 200:
        raise bottle.HTTPResponse(
            body='{"error":"bad_request","detail":"q is too long"}',
            status=400, headers={"Content-Type": "application/json"},
        )

    focus_lat_str = bottle.request.query.get("focus_lat")
    focus_lon_str = bottle.request.query.get("focus_lon")
    if (focus_lat_str is None) != (focus_lon_str is None):
        raise bottle.HTTPResponse(
            body='{"error":"bad_request","detail":"focus_lat and focus_lon must be provided together"}',
            status=400, headers={"Content-Type": "application/json"},
        )
    focus_lat = focus_lon = None
    if focus_lat_str is not None:
        try:
            focus_lat, focus_lon = float(focus_lat_str), float(focus_lon_str)
        except ValueError:
            raise bottle.HTTPResponse(
                body='{"error":"bad_request","detail":"focus_lat and focus_lon must be numeric"}',
                status=400, headers={"Content-Type": "application/json"},
            )

    limit = _int_param("limit", 10)
    accept_language = bottle.request.environ.get("HTTP_ACCEPT_LANGUAGE")
    bottle.response.content_type = "application/json"
    return json.dumps(geocode(q, focus_lat, focus_lon, limit, accept_language))


@app.route("/api/v2/rest/stops/<stop_id:int>/departures")
@require_auth
@require_rate_limit
def departures(stop_id):
    next_seconds = _int_param("next", 3600)
    bottle.response.content_type = "application/json"
    return json.dumps(get_departures(stop_id, next_seconds))


@app.route("/api/v2/rest/departures")
@require_auth
@require_rate_limit
def departures_batch():
    onestop_ids_param = bottle.request.query.get("onestop_ids")
    if not onestop_ids_param:
        raise bottle.HTTPResponse(
            body='{"error":"bad_request","detail":"onestop_ids is required"}',
            status=400, headers={"Content-Type": "application/json"},
        )

    onestop_ids = [s.strip() for s in onestop_ids_param.split(",") if s.strip()]

    if len(onestop_ids) == 0:
        raise bottle.HTTPResponse(
            body='{"error":"bad_request","detail":"onestop_ids is required"}',
            status=400, headers={"Content-Type": "application/json"},
        )

    if len(onestop_ids) > _BATCH_MAX_STOPS:
        raise bottle.HTTPResponse(
            body='{"error":"bad_request","detail":"too many onestop_ids"}',
            status=400, headers={"Content-Type": "application/json"},
        )

    next_seconds = _int_param("next", 3600)
    bottle.response.content_type = "application/json"
    return json.dumps(get_departures_by_onestop_ids(onestop_ids, next_seconds))


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
