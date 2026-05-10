import hmac
import json
import bottle
from auth import require_auth
from rate_limit import require_rate_limit
from proxy import get_stops, get_departures, get_departures_by_onestop_ids, geocode, _BATCH_MAX_STOPS
from config import load_config
from stats import load_stats, render_html

_cfg = load_config()
_stats_secret = _cfg["STATS_PATH_SECRET"]

app = bottle.Bottle()


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

    radius = bottle.request.query.get("radius", 500)
    limit = bottle.request.query.get("limit", 20)
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

    limit = bottle.request.query.get("limit", 10)
    accept_language = bottle.request.environ.get("HTTP_ACCEPT_LANGUAGE")
    bottle.response.content_type = "application/json"
    return json.dumps(geocode(q, focus_lat, focus_lon, limit, accept_language))


@app.route("/api/v2/rest/stops/<stop_id:int>/departures")
@require_auth
@require_rate_limit
def departures(stop_id):
    next_seconds = bottle.request.query.get("next", 7200)
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

    next_seconds = bottle.request.query.get("next", 7200)
    bottle.response.content_type = "application/json"
    return json.dumps(get_departures_by_onestop_ids(onestop_ids, next_seconds))


def _stats_check(token):
    """Return True if the token matches the configured secret."""
    return bool(_stats_secret) and hmac.compare_digest(token, _stats_secret)


@app.route("/_internal/<token>/stats.json")
def stats_json(token):
    if not _stats_check(token):
        raise bottle.HTTPResponse(status=404, body="Not Found")
    bottle.response.content_type = "application/json"
    return json.dumps(load_stats())


@app.route("/_internal/<token>/stats")
def stats_html(token):
    if not _stats_check(token):
        raise bottle.HTTPResponse(status=404, body="Not Found")
    bottle.response.content_type = "text/html; charset=utf-8"
    return render_html(load_stats())
