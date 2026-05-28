"""511.org GTFS-Realtime + static feed integration.

Public surface used by `proxy.py` and `scripts/bench_511.py`:

    is_bay_area_feed(feed_onestop_id) -> bool
    refresh_if_stale(metrics=None) -> RefreshOutcome
    lookup_departures(feed_stop_keys, next_seconds) -> list[dict]
    refresh_static(metrics=None) -> RefreshOutcome  (bench-only)

Everything is implemented on top of two SQLite files under
GTFS_511_DB_DIR:

    gtfs_511_static.sqlite   -- static GTFS, refreshed weekly
    gtfs_511_rt.sqlite       -- realtime overlay, rewritten every minute
"""

from .api import (
    is_bay_area_feed,
    refresh_if_stale,
    refresh_static,
    refresh_static_locked,
    lookup_departures,
    RefreshOutcome,
    RefreshMetrics,
)

__all__ = [
    "is_bay_area_feed",
    "refresh_if_stale",
    "refresh_static",
    "refresh_static_locked",
    "lookup_departures",
    "RefreshOutcome",
    "RefreshMetrics",
]
