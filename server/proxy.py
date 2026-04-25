import json
import math
import sys
import requests
import bottle
from config import load_config

__version__ = "1.0"

_cfg = load_config()
_base_url = _cfg["TRANSITLAND_BASE_URL"]
_api_key = _cfg["TRANSITLAND_API_KEY"]
_connect_timeout = _cfg["UPSTREAM_CONNECT_TIMEOUT"]
_read_timeout = _cfg["UPSTREAM_READ_TIMEOUT"]

_session = requests.Session()
_session.headers.update({
    "User-Agent": f"CatchTheNext-Proxy/{__version__} (+https://codeberg.org/ursidaureus/catch_the_next)"
})


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


# Upstream sorts by stop_name, not distance — so a low limit can truncate the
# closest stops if many alphabetically-earlier stops are within radius. Always
# fetch a wide candidate set, then sort by distance and trim to `limit`.
_UPSTREAM_STOPS_LIMIT = 100


def get_stops(lat, lon, radius=500, limit=20):
    radius = min(int(radius), 2000)
    limit = min(int(limit), 50)
    data = _upstream_get("stops", {
        "lat": lat, "lon": lon, "radius": radius, "limit": _UPSTREAM_STOPS_LIMIT,
    })

    stops = []
    for s in data.get("stops", []):
        geom = s.get("geometry", {}).get("coordinates", [None, None])
        slat, slon = geom[1], geom[0]
        if slat is None or slon is None:
            continue
        feed_version = s.get("feed_version") or {}
        feed = feed_version.get("feed") or {}
        license_info = feed.get("license") or {}
        use_without = license_info.get("use_without_attribution")
        stops.append({
            "id": s.get("id"),
            "stop_id": s.get("stop_id"),
            "stop_name": s.get("stop_name"),
            "lat": slat,
            "lon": slon,
            "onestop_id": s.get("onestop_id"),
            "feed_onestop_id": feed.get("onestop_id"),
            "feed_name": feed.get("name"),
            "attribution_text": license_info.get("attribution_text"),
            "attribution_instructions": license_info.get("attribution_instructions"),
            "use_without_attribution": use_without in ("yes", True, "true"),
            "license_spdx": license_info.get("spdx_identifier"),
            "license_url": license_info.get("url"),
            "_dist_m": _haversine_meters(lat, lon, slat, slon),
        })

    stops.sort(key=lambda s: s["_dist_m"])
    stops = stops[:limit]
    for s in stops:
        del s["_dist_m"]
    return {"stops": stops}


def _haversine_meters(lat1, lon1, lat2, lon2):
    r = 6_371_000.0
    phi1 = math.radians(lat1)
    phi2 = math.radians(lat2)
    d_phi = math.radians(lat2 - lat1)
    d_lambda = math.radians(lon2 - lon1)
    a = math.sin(d_phi / 2) ** 2 + math.cos(phi1) * math.cos(phi2) * math.sin(d_lambda / 2) ** 2
    return r * 2 * math.atan2(math.sqrt(a), math.sqrt(1 - a))


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
            agency = route.get("agency") or {}
            route_feed_version = route.get("feed_version") or {}
            route_feed = route_feed_version.get("feed") or {}
            route_license = route_feed.get("license") or {}
            use_without = route_license.get("use_without_attribution")
            departures.append({
                "route_short_name": route.get("route_short_name", ""),
                "headsign": (dep.get("trip") or {}).get("trip_headsign", ""),
                "departure_minutes": minutes,
                "departure_time": scheduled_utc or gtfs_offset,
                "schedule_relationship": dep.get("schedule_relationship", "SCHEDULED"),
                "agency_name": agency.get("agency_name"),
                "feed_onestop_id": route_feed.get("onestop_id"),
                "feed_name": route_feed.get("name"),
                "attribution_text": route_license.get("attribution_text"),
                "use_without_attribution": use_without in ("yes", True, "true"),
                "license_spdx": route_license.get("spdx_identifier"),
                "license_url": route_license.get("url"),
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
