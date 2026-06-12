"""Helpers shared between the proxy and the gtfs511 package."""

import math


def haversine_meters(lat1, lon1, lat2, lon2):
    r = 6_371_000.0
    phi1 = math.radians(lat1)
    phi2 = math.radians(lat2)
    d_phi = math.radians(lat2 - lat1)
    d_lambda = math.radians(lon2 - lon1)
    a = math.sin(d_phi / 2) ** 2 + math.cos(phi1) * math.cos(phi2) * math.sin(d_lambda / 2) ** 2
    return r * 2 * math.atan2(math.sqrt(a), math.sqrt(1 - a))


def alert_periods_active(periods, now):
    """GTFS-RT active_period semantics: no periods = always active; periods ORed."""
    if not periods:
        return True
    for period in periods:
        start = period.get("start")
        end = period.get("end")
        if ((not start) or start <= now) and ((not end) or end >= now):
            return True
    return False
