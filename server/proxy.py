import json
import math
import sys
import threading
import time
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

_NOMINATIM_BASE_URL = "https://nominatim.openstreetmap.org"
_nominatim_session = requests.Session()
_nominatim_session.headers.update({
    "User-Agent": f"CatchTheNext-Proxy/{__version__} (+https://codeberg.org/ursidaureus/catch_the_next)"
})
_nominatim_lock = threading.Lock()
_nominatim_last_call = 0.0


def _nominatim_throttle():
    global _nominatim_last_call
    with _nominatim_lock:
        now = time.monotonic()
        wait = 1.0 - (now - _nominatim_last_call)
        if wait > 0:
            time.sleep(wait)
        _nominatim_last_call = time.monotonic()


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


def geocode(query, focus_lat=None, focus_lon=None, limit=10, accept_language=None):
    limit = min(int(limit), 10)
    params = {
        "q": query,
        "format": "jsonv2",
        "limit": limit,
        "addressdetails": 0,
    }
    if focus_lat is not None and focus_lon is not None:
        params["viewbox"] = f"{focus_lon - 0.5},{focus_lat + 0.5},{focus_lon + 0.5},{focus_lat - 0.5}"
        params["bounded"] = 0

    headers = {}
    if accept_language:
        headers["Accept-Language"] = accept_language

    _nominatim_throttle()
    url = f"{_NOMINATIM_BASE_URL}/search"
    try:
        resp = _nominatim_session.get(url, params=params, headers=headers,
                                      timeout=(_connect_timeout, _read_timeout))
    except requests.Timeout:
        raise bottle.HTTPResponse(
            body='{"error":"upstream_timeout"}', status=504,
            headers={"Content-Type": "application/json"},
        )
    except requests.RequestException as e:
        print(f"nominatim error: {e}", file=sys.stderr)
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

    places = []
    for item in resp.json():
        places.append({
            "place_id": str(item.get("place_id", "")),
            "display_name": item.get("display_name", ""),
            "lat": float(item.get("lat", 0)),
            "lon": float(item.get("lon", 0)),
            "category": item.get("category"),
            "type": item.get("type"),
        })
    return {"places": places}


def get_stops(lat, lon, radius=500, limit=20):
    radius = min(int(radius), 5000)
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


def _resolve_translation(arr):
    if not arr:
        return None
    for item in arr:
        if item.get("language") == "en" and item.get("text"):
            return item["text"]
    for item in arr:
        if item.get("text"):
            return item["text"]
    return None


def _alert_is_active(alert, now):
    periods = alert.get("active_period") or []
    if not periods:
        return True
    for period in periods:
        start = period.get("start")
        end = period.get("end")
        start_ok = (not start) or start <= now
        end_ok = (not end) or end >= now
        if start_ok and end_ok:
            return True
    return False


def _shape_alerts(data, now=None):
    if now is None:
        from datetime import datetime, timezone
        now = int(datetime.now(tz=timezone.utc).timestamp())

    seen = set()
    alerts = []

    def collect_stop(stop_data):
        for alert in stop_data.get("alerts") or []:
            header = _resolve_translation(alert.get("header_text") or [])
            description = _resolve_translation(alert.get("description_text") or [])
            cause = alert.get("cause")
            effect = alert.get("effect")
            key = (header, description, cause, effect)
            if key in seen:
                continue
            if not _alert_is_active(alert, now):
                continue
            seen.add(key)
            alerts.append({
                "cause": cause,
                "effect": effect,
                "severity_level": alert.get("severity_level"),
                "header_text": header,
                "description_text": description,
                "tts_header_text": _resolve_translation(alert.get("tts_header_text") or []),
                "tts_description_text": _resolve_translation(alert.get("tts_description_text") or []),
                "url": _resolve_translation(alert.get("url") or []),
                "active_period": [
                    {"start": p.get("start"), "end": p.get("end")}
                    for p in (alert.get("active_period") or [])
                ],
            })

    for stop_data in data.get("stops", []):
        collect_stop(stop_data)
        parent = stop_data.get("parent")
        if parent:
            collect_stop(parent)
        for child in stop_data.get("children") or []:
            collect_stop(child)

    return alerts


def _classify_time_source(stt, schedule_relationship):
    estimated_utc = (stt or {}).get("estimated_utc")
    if estimated_utc and schedule_relationship != "STATIC":
        return "LIVE"
    return "SCHEDULED"


def _shape_departures(data):
    departures = []
    now_minutes = _now_minutes()

    def collect(stop_data):
        for dep in stop_data.get("departures") or []:
            stt = dep.get("departure") or {}
            scheduled_utc = stt.get("scheduled_utc", "")
            estimated_utc = stt.get("estimated_utc", "")
            scheduled_local = stt.get("scheduled_local", "")
            estimated_local = stt.get("estimated_local", "")
            gtfs_offset = dep.get("departure_time", "")
            schedule_relationship = dep.get("schedule_relationship", "SCHEDULED")

            sched_minutes = _parse_minutes(scheduled_utc, gtfs_offset, now_minutes)
            live_minutes = _parse_minutes(estimated_utc, None, now_minutes) if estimated_utc else None

            time_source = _classify_time_source(stt, schedule_relationship)

            if time_source == "LIVE" and live_minutes is not None:
                effective_minutes = live_minutes
            elif sched_minutes is not None:
                effective_minutes = sched_minutes
            else:
                continue

            if effective_minutes < 0:
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
                "scheduled_departure_time": scheduled_local or scheduled_utc or gtfs_offset or None,
                "scheduled_departure_utc": scheduled_utc or None,
                "scheduled_departure_minutes": sched_minutes,
                "live_departure_time": estimated_local or estimated_utc or None,
                "live_departure_utc": estimated_utc or None,
                "live_departure_minutes": live_minutes,
                "time_source": time_source,
                "schedule_relationship": schedule_relationship,
                "agency_name": agency.get("agency_name"),
                "feed_onestop_id": route_feed.get("onestop_id"),
                "feed_name": route_feed.get("name"),
                "attribution_text": route_license.get("attribution_text"),
                "attribution_instructions": route_license.get("attribution_instructions"),
                "use_without_attribution": use_without in ("yes", True, "true"),
                "license_spdx": route_license.get("spdx_identifier"),
                "license_url": route_license.get("url"),
            })
        for child in stop_data.get("children") or []:
            collect(child)

    for s in data.get("stops", []):
        collect(s)

    departures.sort(key=lambda d: d["scheduled_departure_minutes"] if d["time_source"] == "SCHEDULED" else (d["live_departure_minutes"] if d["live_departure_minutes"] is not None else d["scheduled_departure_minutes"]))
    return departures


def get_departures(stop_id, next_seconds=7200):
    next_seconds = min(int(next_seconds), 86400)
    data = _upstream_get(
        f"stops/{stop_id}/departures",
        {"next": next_seconds, "relative_date": "TODAY", "include_alerts": "true"},
    )
    return {"departures": _shape_departures(data), "alerts": _shape_alerts(data)}


_BATCH_MAX_STOPS = 6


def get_departures_batch(stop_ids, next_seconds=7200):
    next_seconds = min(int(next_seconds), 86400)
    stops = []
    for stop_id in stop_ids:
        data = _upstream_get(
            f"stops/{stop_id}/departures",
            {"next": next_seconds, "relative_date": "TODAY", "include_alerts": "true"},
        )
        stops.append({
            "stop_id": stop_id,
            "departures": _shape_departures(data),
            "alerts": _shape_alerts(data),
        })
    return {"stops": stops}


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
