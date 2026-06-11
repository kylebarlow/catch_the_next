import json
import math
import socket
import sys
import threading
import time
import zlib
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor

import bottle
import cache
import counters
from config import load_config

try:
    import gtfs511  # noqa: F401  — optional, may be disabled by config
    _GTFS511_IMPORTED = True
except Exception as _e:
    print(f"gtfs511 import failed; 511 path disabled: {_e}", file=sys.stderr)
    _GTFS511_IMPORTED = False

__version__ = "1.0"

_cfg = load_config()
_base_url = _cfg["TRANSITLAND_BASE_URL"]
_api_key = _cfg["TRANSITLAND_API_KEY"]
_connect_timeout = _cfg["UPSTREAM_CONNECT_TIMEOUT"]
_read_timeout = _cfg["UPSTREAM_READ_TIMEOUT"]
_RT_MAX_STALE = _cfg["FIVE_ELEVEN_RT_MAX_STALE"]

_USER_AGENT = f"CatchTheNext-Proxy/{__version__} (+https://codeberg.org/ursidaureus/catch_the_next)"

_NOMINATIM_BASE_URL = "https://nominatim.openstreetmap.org"
_nominatim_lock = threading.Lock()
_nominatim_last_call = 0.0

# Response-cache TTL (seconds) used for departure/stop query results.
_RESPONSE_CACHE_TTL = 50
# Geocode results change rarely; 1h is safe.
_GEOCODE_CACHE_TTL = 3600
# _resolve_stop_info records only classify a stop as Bay-Area-or-not and supply
# its feed/stop_id; the TL departures call is now keyed by onestop_id directly,
# so integer-id staleness is irrelevant and the record can live a long time.
_STOP_ID_CACHE_TTL = 86400 * 180  # 180 days

_BATCH_MAX_STOPS = 6

# ── Synthetic onestop_id helpers ────────────────────────────────────────────
# Stops discovered via the local 511 static DB carry a server-synthesized
# onestop_id "511:<feed_onestop_id>:<stop_id>". The Kotlin client treats it as
# an opaque string, so it round-trips with zero client changes. They have no
# Transitland identity — they always serve from the local 511 path.
#
# CAVEAT: disabling 511 after synthetic ids exist is a one-way door — those
# favorites would go dark (empty departures) since they can't be served by TL.
_SYNTHETIC_PREFIX = "511:"


def _parse_synthetic_onestop_id(oid):
    """Return (feed_id, stop_id) for a synthetic id, else None.

    Requires 3 non-empty parts and a recognized Bay Area feed. ':' inside the
    stop_id is preserved (split with maxsplit=2).
    """
    if not isinstance(oid, str) or not oid.startswith(_SYNTHETIC_PREFIX):
        return None
    parts = oid.split(":", 2)
    if len(parts) != 3:
        return None
    _, feed_id, stop_id = parts
    if not feed_id or not stop_id:
        return None
    if not (_GTFS511_IMPORTED and gtfs511.is_bay_area_feed(feed_id)):
        return None
    return feed_id, stop_id


def _make_synthetic_onestop_id(feed_id, stop_id):
    """Build a synthetic id, or None if stop_id is empty or contains ',' (the
    client's batch separator)."""
    if not stop_id or "," in str(stop_id):
        return None
    return f"{_SYNTHETIC_PREFIX}{feed_id}:{stop_id}"


def _synthetic_integer_id(onestop_id: str) -> int:
    """Stable negative integer id for a synthetic onestop_id.

    crc32 (not hash(), which is salted per-process) keeps it stable across
    processes; negative so it can't collide with Transitland's positive ids.
    """
    return -((zlib.crc32(onestop_id.encode()) % 0x7FFFFFFF) + 1)


def _http_get_json(url: str, params: dict | None = None,
                   headers: dict | None = None,
                   timeout: tuple | None = None,
                   allow_404: bool = False):
    """GET *url* with optional query *params*, return parsed JSON body.

    Raises bottle.HTTPResponse 504 on timeout, 502 on other errors.
    If *allow_404* is True, returns None when the server responds 404
    (used to detect stale cached stop integer IDs).
    """
    if params:
        url = url + "?" + urllib.parse.urlencode(params)
    req = urllib.request.Request(url)
    req.add_header("User-Agent", _USER_AGENT)
    if headers:
        for k, v in headers.items():
            req.add_header(k, v)

    connect_t, read_t = (timeout if timeout else (_connect_timeout, _read_timeout))
    try:
        with urllib.request.urlopen(req, timeout=connect_t + read_t) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        if allow_404 and e.code == 404:
            return None
        print(f"upstream HTTP {e.code} for {url}", file=sys.stderr)
        raise bottle.HTTPResponse(
            body=json.dumps({"error": "upstream", "status": e.code}),
            status=502,
            headers={"Content-Type": "application/json"},
        )
    except (socket.timeout, TimeoutError):
        raise bottle.HTTPResponse(
            body='{"error":"upstream_timeout"}', status=504,
            headers={"Content-Type": "application/json"},
        )
    except (urllib.error.URLError, OSError) as e:
        print(f"upstream error: {e}", file=sys.stderr)
        raise bottle.HTTPResponse(
            body='{"error":"upstream"}', status=502,
            headers={"Content-Type": "application/json"},
        )


def _upstream_get(path: str, params: dict, allow_404: bool = False):
    params = dict(params)
    params["apikey"] = _api_key
    url = f"{_base_url}/{path}"
    return _http_get_json(url, params=params, allow_404=allow_404)


def _nominatim_throttle():
    global _nominatim_last_call
    with _nominatim_lock:
        now = time.monotonic()
        wait = 1.0 - (now - _nominatim_last_call)
        if wait > 0:
            time.sleep(wait)
        _nominatim_last_call = time.monotonic()


# Upstream sorts by stop_name, not distance — so a low limit can truncate the
# closest stops if many alphabetically-earlier stops are within radius. Always
# fetch a wide candidate set, then sort by distance and trim to `limit`.
_UPSTREAM_STOPS_LIMIT = 100


def geocode(query, focus_lat=None, focus_lon=None, limit=10, accept_language=None):
    limit = min(int(limit), 10)
    cache_key = f"geo:{query}:{focus_lat}:{focus_lon}:{limit}:{accept_language}"
    cached = cache.get(cache_key)
    if cached is not None:
        return cached

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
    data = _http_get_json(
        f"{_NOMINATIM_BASE_URL}/search",
        params=params,
        headers=headers,
        timeout=(_connect_timeout, _read_timeout),
    )
    counters.increment("nominatim_calls")

    places = []
    for item in data:
        places.append({
            "place_id": str(item.get("place_id", "")),
            "display_name": item.get("display_name", ""),
            "lat": float(item.get("lat", 0)),
            "lon": float(item.get("lon", 0)),
            "category": item.get("category"),
            "type": item.get("type"),
        })
    result = {"places": places}
    cache.set(cache_key, result, _GEOCODE_CACHE_TTL)
    return result


# Grid-snapped caching for the Transitland get_stops path. We round the query
# coords to a coarse grid cell and fetch the cell center + slack so nearby
# requests share an upstream call.
_STOPS_GRID_DEG = 0.002          # ~222m
_STOPS_GRID_SLACK_M = 300
_STOPS_GRID_CACHE_TTL = 6 * 3600


def _shape_local_stop(row) -> dict | None:
    """Shape a local 511 static-DB stop row into the 13-key stop dict, or None
    if it can't be assigned a synthetic onestop_id."""
    onestop_id = _make_synthetic_onestop_id(gtfs511.REGIONAL_FEED_ID, row["stop_id"])
    if onestop_id is None:
        return None
    meta = gtfs511.metadata_for(gtfs511.REGIONAL_FEED_ID)
    return {
        "id": _synthetic_integer_id(onestop_id),
        "stop_id": row["stop_id"],
        "stop_name": row["stop_name"],
        "lat": row["stop_lat"],
        "lon": row["stop_lon"],
        "onestop_id": onestop_id,
        "feed_onestop_id": gtfs511.REGIONAL_FEED_ID,
        "feed_name": meta.get("feed_name"),
        "attribution_text": meta.get("attribution_text"),
        "attribution_instructions": meta.get("attribution_instructions"),
        "use_without_attribution": bool(meta.get("use_without_attribution")),
        "license_spdx": meta.get("license_spdx"),
        "license_url": meta.get("license_url"),
    }


def get_stops(lat, lon, radius=500, limit=20):
    radius = min(int(radius), 5000)
    limit = min(int(limit), 50)

    # Local Bay Area path: serve from the 511 static DB with no Transitland
    # call and no cache write (a local SQLite read needs no caching).
    if _GTFS511_IMPORTED and gtfs511.in_bay_area(float(lat), float(lon)):
        rows = gtfs511.nearby_stops(float(lat), float(lon), radius, limit)
        if rows is not None:  # None => static missing/disabled => fall through to TL
            stops = []
            for row in rows:
                shaped = _shape_local_stop(row)
                if shaped is not None:
                    stops.append(shaped)
            return {"stops": stops}

    # Transitland path with grid-snapped caching.
    cell_lat = round(float(lat) / _STOPS_GRID_DEG)
    cell_lon = round(float(lon) / _STOPS_GRID_DEG)
    upstream_radius = min(radius + _STOPS_GRID_SLACK_M, 5000)
    cache_key = f"stops_g:{cell_lat}:{cell_lon}:{radius}"
    data = cache.get(cache_key)
    if data is None:
        data = _upstream_get("stops", {
            "lat": cell_lat * _STOPS_GRID_DEG,
            "lon": cell_lon * _STOPS_GRID_DEG,
            "radius": upstream_radius,
            "limit": _UPSTREAM_STOPS_LIMIT,
        })
        counters.increment("transitland_calls")
        cache.set(cache_key, data, _STOPS_GRID_CACHE_TTL)

    stops = []
    for s in data.get("stops", []):
        geom = s.get("geometry", {}).get("coordinates", [None, None])
        slat, slon = geom[1], geom[0]
        if slat is None or slon is None:
            continue
        # Haversine to the TRUE requested coords (not the grid center); post-
        # filter so slack-radius results don't leak past the requested radius.
        dist = _haversine_meters(lat, lon, slat, slon)
        if dist > radius:
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
            "_dist_m": dist,
        })

    stops.sort(key=lambda s: s["_dist_m"])
    stops = stops[:limit]
    for s in stops:
        del s["_dist_m"]
    # NOTE: _UPSTREAM_STOPS_LIMIT=100 alphabetical truncation worsens slightly
    # with slack; at radius=5000 the cap can miss boundary stops within ~141m
    # of the edge.
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

    def collect(stop_data):
        for dep in stop_data.get("departures") or []:
            stt = dep.get("departure") or {}
            scheduled_utc = stt.get("scheduled_utc", "")
            estimated_utc = stt.get("estimated_utc", "")
            scheduled_local = stt.get("scheduled_local", "")
            estimated_local = stt.get("estimated_local", "")
            gtfs_offset = dep.get("departure_time", "")
            schedule_relationship = dep.get("schedule_relationship", "SCHEDULED")

            sched_minutes = _parse_minutes(scheduled_utc)
            live_minutes = _parse_minutes(estimated_utc) if estimated_utc else None

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


def get_departures(stop_id, next_seconds=3600):
    next_seconds = min(int(next_seconds), 86400)
    cache_key = f"dep:{stop_id}:{next_seconds}"
    cached = cache.get(cache_key)
    if cached is not None:
        return cached
    data = _upstream_get(
        f"stops/{stop_id}/departures",
        {"next": next_seconds, "relative_date": "TODAY", "include_alerts": "true"},
    )
    counters.increment("transitland_calls")
    result = {"departures": _shape_departures(data), "alerts": _shape_alerts(data)}
    cache.set(cache_key, result, _RESPONSE_CACHE_TTL)
    return result


def _resolve_stop_info(oid: str):
    """Return ``{integer_id, feed_onestop_id, stop_id}`` for *oid* or None.

    Cached for _STOP_ID_CACHE_TTL. The richer record (vs. just the integer)
    lets us route Bay Area stops to the 511 path without an extra upstream
    hit. Backwards-compatible with prior cache rows that stored only the int.
    """
    cache_key = f"oid_full:{oid}"
    cached = cache.get(cache_key)
    if isinstance(cached, dict) and "integer_id" in cached:
        return cached

    data = _upstream_get("stops", {"onestop_id": oid, "limit": 1})
    counters.increment("transitland_calls")
    for s in (data or {}).get("stops", []):
        if s.get("onestop_id") == oid and s.get("id"):
            feed = (s.get("feed_version") or {}).get("feed") or {}
            info = {
                "integer_id": s["id"],
                "feed_onestop_id": feed.get("onestop_id"),
                "stop_id": s.get("stop_id"),
            }
            cache.set(cache_key, info, _STOP_ID_CACHE_TTL)
            return info
    return None


def _refresh_511_state() -> dict:
    """Refresh the 511 caches once per request. Never raises.

    Returns {"static_present": bool, "rt_fresh": bool, "rt_age": int|None}.
    rt_fresh means the RT snapshot is no older than _RT_MAX_STALE.
    """
    if not _GTFS511_IMPORTED or not gtfs511.enabled():
        return {"static_present": False, "rt_fresh": False, "rt_age": None}
    try:
        outcome = gtfs511.refresh_if_stale()
        static_age = outcome.static_age_seconds
        rt_age = outcome.rt_age_seconds
    except Exception as e:  # noqa: BLE001 — refresh_if_stale shouldn't raise; safety net
        print(f"gtfs511 refresh_if_stale raised: {e}", file=sys.stderr)
        static_age, rt_age = gtfs511.data_ages()
    return {
        "static_present": static_age is not None,
        "rt_fresh": rt_age is not None and rt_age <= _RT_MAX_STALE,
        "rt_age": rt_age,
    }


def _tl_departures(oid: str, next_seconds: int) -> dict:
    """Fetch departures for *oid* from Transitland, keyed by onestop_id."""
    data = _upstream_get(
        f"stops/{urllib.parse.quote(oid, safe='')}/departures",
        {"next": next_seconds, "relative_date": "TODAY", "include_alerts": "true"},
        allow_404=True,
    )
    counters.increment("transitland_calls")
    if data is None:  # 404 — treat as empty
        return {"onestop_id": oid, "departures": [], "alerts": []}
    return {
        "onestop_id": oid,
        "departures": _shape_departures(data),
        "alerts": _shape_alerts(data),
    }


def _local_departures(oid: str, feed_id: str, stop_id: str, next_seconds: int) -> dict:
    result = gtfs511.lookup_departures([(feed_id, stop_id)], next_seconds)
    counters.increment("five11_calls")
    entry = result.get((feed_id, stop_id)) or {}
    return {
        "onestop_id": oid,
        "departures": entry.get("departures", []),
        "alerts": entry.get("alerts", []),
    }


def get_departures_by_onestop_ids(onestop_ids, next_seconds=3600):
    next_seconds = min(int(next_seconds), 86400)
    cache_key = f"departures:{','.join(sorted(onestop_ids))}:{next_seconds}"
    cached = cache.get(cache_key)
    if cached is not None:
        return cached

    # Classification pass (sequential, before the pool): build one plan per oid.
    #   ("local_always", feed_id, stop_id) — synthetic id, always served locally
    #   ("local_or_tl", feed_id, stop_id)  — Bay Area TL stop, local if RT fresh
    #   ("tl", oid)                        — non-Bay-Area, Transitland only
    #   ("none", oid)                      — unresolvable, empty result
    plans = {}
    need_511 = False
    for oid in onestop_ids:
        syn = _parse_synthetic_onestop_id(oid)
        if syn is not None:
            plans[oid] = ("local_always", syn[0], syn[1])
            need_511 = True
            continue
        info = _resolve_stop_info(oid)
        if info is None:
            plans[oid] = ("none", oid)
        elif _GTFS511_IMPORTED and gtfs511.is_bay_area_feed(info.get("feed_onestop_id")):
            plans[oid] = ("local_or_tl", info["feed_onestop_id"], info["stop_id"])
            need_511 = True
        else:
            plans[oid] = ("tl", oid)

    state = _refresh_511_state() if need_511 else None

    def _fetch_one(oid: str):
        plan = plans[oid]
        kind = plan[0]
        if kind == "none":
            return {"onestop_id": oid, "departures": [], "alerts": []}
        if kind == "local_always":
            _, feed_id, stop_id = plan
            # Synthetic ids have no TL identity — serve locally regardless of RT
            # staleness (schedule data degrades gracefully). Never call TL.
            if state and state["static_present"]:
                try:
                    return _local_departures(oid, feed_id, stop_id, next_seconds)
                except Exception as e:  # noqa: BLE001
                    print(f"gtfs511 local_always lookup for {oid}: {e}", file=sys.stderr)
            return {"onestop_id": oid, "departures": [], "alerts": []}
        if kind == "local_or_tl":
            _, feed_id, stop_id = plan
            if state and state["static_present"] and state["rt_fresh"]:
                try:
                    return _local_departures(oid, feed_id, stop_id, next_seconds)
                except Exception as e:  # noqa: BLE001
                    print(f"gtfs511 fallback for {oid}: {e}", file=sys.stderr)
            # RT missing / stale / static missing / lookup failed → Transitland.
            return _tl_departures(oid, next_seconds)
        # kind == "tl"
        return _tl_departures(oid, next_seconds)

    with ThreadPoolExecutor(max_workers=max(len(onestop_ids), 1)) as pool:
        stops = list(pool.map(_fetch_one, onestop_ids))

    result = {"stops": stops}
    cache.set(cache_key, result, _RESPONSE_CACHE_TTL)
    return result


def _parse_minutes(scheduled_utc):
    if scheduled_utc:
        try:
            from datetime import datetime, timezone
            dt = datetime.fromisoformat(scheduled_utc.replace("Z", "+00:00"))
            now = datetime.now(tz=timezone.utc)
            return int((dt - now).total_seconds() // 60)
        except (ValueError, TypeError):
            pass
    return None
