import json
import bottle
from auth import require_auth
from rate_limit import require_rate_limit
from proxy import get_stops, get_departures


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


@app.route("/api/v2/rest/stops/<stop_id:int>/departures")
@require_auth
@require_rate_limit
def departures(stop_id):
    next_seconds = bottle.request.query.get("next", 7200)
    bottle.response.content_type = "application/json"
    return json.dumps(get_departures(stop_id, next_seconds))
