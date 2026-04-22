import json
import sys
import requests
import bottle
from config import load_config

_cfg = load_config()
_base_url = _cfg["TRANSITLAND_BASE_URL"]
_api_key = _cfg["TRANSITLAND_API_KEY"]
_connect_timeout = _cfg["UPSTREAM_CONNECT_TIMEOUT"]
_read_timeout = _cfg["UPSTREAM_READ_TIMEOUT"]

_session = requests.Session()


def _upstream_get(path, params):
    params = dict(params)
    params["apikey"] = _api_key
    url = f"{_base_url}/{path}"
    try:
        resp = _session.get(url, params=params, timeout=(_connect_timeout, _read_timeout))
    except requests.Timeout:
        raise bottle.HTTPResponse(
            body='{"error":"upstream_timeout"}', status=504,
            headers={"Content-Type": "application/json"},
        )
    except requests.RequestException as e:
        print(f"upstream error: {e}", file=sys.stderr)
        raise bottle.HTTPResponse(
            body='{"error":"upstream"}', status=502,
            headers={"Content-Type": "application/json"},
        )

    if not resp.ok:
        raise bottle.HTTPResponse(
            body=json.dumps({"error": "upstream", "status": resp.status_code}),
            status=502,
            headers={"Content-Type": "application/json"},
        )

    return resp.json()


def get_stops(lat, lon, radius=500, limit=20):
    radius = min(int(radius), 2000)
    limit = min(int(limit), 50)
    data = _upstream_get("stops", {"lat": lat, "lon": lon, "radius": radius, "limit": limit})

    stops = []
    for s in data.get("stops", []):
        geom = s.get("geometry", {}).get("coordinates", [None, None])
        stops.append({
            "id": s.get("id"),
            "stop_id": s.get("stop_id"),
            "stop_name": s.get("stop_name"),
            "lat": geom[1],
            "lon": geom[0],
            "onestop_id": s.get("onestop_id"),
        })
    return {"stops": stops}


def get_departures(stop_id, next_seconds=7200):
    next_seconds = min(int(next_seconds), 86400)
    data = _upstream_get(
        f"stops/{stop_id}/departures",
        {"next": next_seconds, "relative_date": "TODAY"},
    )

    departures = []
    now_minutes = _now_minutes()

    def collect(stop_data):
        for dep in stop_data.get("departures") or []:
            stt = dep.get("departure") or {}
            scheduled_utc = stt.get("scheduled_utc", "")
            gtfs_offset = dep.get("departure_time", "")
            minutes = _parse_minutes(scheduled_utc, gtfs_offset, now_minutes)
            if minutes is None or minutes < 0:
                continue
            route = (dep.get("trip") or {}).get("route") or {}
            departures.append({
                "route_short_name": route.get("route_short_name", ""),
                "headsign": (dep.get("trip") or {}).get("trip_headsign", ""),
                "departure_minutes": minutes,
                "departure_time": scheduled_utc or gtfs_offset,
                "schedule_relationship": dep.get("schedule_relationship", "SCHEDULED"),
            })
        for child in stop_data.get("children") or []:
            collect(child)

    for s in data.get("stops", []):
        collect(s)

    departures.sort(key=lambda d: d["departure_minutes"])
    return {"departures": departures}


def _now_minutes():
    from datetime import datetime, timezone
    now = datetime.now(tz=timezone.utc)
    return now.hour * 60 + now.minute


def _parse_minutes(scheduled_utc, gtfs_offset, now_minutes):
    if scheduled_utc:
        try:
            from datetime import datetime, timezone
            dt = datetime.fromisoformat(scheduled_utc.replace("Z", "+00:00"))
            now = datetime.now(tz=timezone.utc)
            return int((dt - now).total_seconds() // 60)
        except (ValueError, TypeError):
            pass
    if gtfs_offset:
        try:
            parts = gtfs_offset.split(":")
            total = int(parts[0]) * 60 + int(parts[1])
            return total - now_minutes
        except (ValueError, IndexError):
            pass
    return None
