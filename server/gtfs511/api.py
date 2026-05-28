"""Public API for the gtfs511 package.

Refresh policy:

  * Static GTFS: rebuilt only when stale (older than FIVE_ELEVEN_STATIC_TTL).
    Static refresh is expensive — measured in seconds — so it should ideally
    happen out-of-band (cron, scheduled task). Lazy refresh on a stale static
    DB will block a single request.

  * Realtime: rebuilt when older than FIVE_ELEVEN_RT_TTL (default 60s, matching
    511's published rate cap). A file lock prevents two concurrent refreshes;
    waiters get the freshly-written snapshot.

  * Lock: SQLite-backed (single-table mutex) so it works across CGI processes
    that don't share memory. Upstream HTTP downloads happen OUTSIDE the lock so
    a slow 511 API doesn't stall concurrent readers. Only the DB build step
    (which is fast, especially with atomic temp-swap) runs under the lock.
    All writers — CGI via refresh_if_stale and cron via refresh_static_locked —
    must go through the lock.
"""

import os
import sqlite3
import time
from dataclasses import dataclass, field
from typing import Iterable

from config import load_config

from . import download, feed_mapping, lookup as lookup_mod, rt_db, static_db

_cfg = load_config()
_DB_DIR = _cfg["GTFS_511_DB_DIR"]
_RT_TTL = _cfg["FIVE_ELEVEN_RT_TTL"]
_STATIC_TTL = _cfg["FIVE_ELEVEN_STATIC_TTL"]
_ENABLED = _cfg["FIVE_ELEVEN_ENABLED"]

STATIC_DB_PATH = os.path.join(_DB_DIR, "gtfs_511_static.sqlite")
RT_DB_PATH = os.path.join(_DB_DIR, "gtfs_511_rt.sqlite")
_LOCK_DB_PATH = os.path.join(_DB_DIR, "refresh.lock.sqlite")


@dataclass
class RefreshMetrics:
    rt_download_bytes: int = 0
    rt_download_seconds: float = 0.0
    alerts_download_bytes: int = 0
    alerts_download_seconds: float = 0.0
    rt_parse_seconds: float = 0.0
    rt_write_seconds: float = 0.0
    rt_rows: int = 0
    alert_rows: int = 0
    static_download_bytes: int = 0
    static_download_seconds: float = 0.0
    static_write_seconds: float = 0.0
    static_rows: dict = field(default_factory=dict)
    static_parse_seconds: dict = field(default_factory=dict)
    final_static_db_bytes: int = 0
    final_rt_db_bytes: int = 0
    cache_hit: bool = False
    waited_for_lock_seconds: float = 0.0


@dataclass
class RefreshOutcome:
    refreshed_rt: bool
    refreshed_static: bool
    rt_age_seconds: int | None
    static_age_seconds: int | None
    metrics: RefreshMetrics


# ─── Public API ───────────────────────────────────────────────────────────────


def enabled() -> bool:
    return _ENABLED


def is_bay_area_feed(feed_onestop_id: str | None) -> bool:
    return enabled() and feed_mapping.is_bay_area_feed(feed_onestop_id)


def refresh_if_stale(metrics: RefreshMetrics | None = None) -> RefreshOutcome:
    """Refresh the RT cache (and static, if missing/expired).

    Downloads happen outside the cross-process lock so a slow 511 API does not
    stall concurrent readers. The lock is held only around the fast DB build.
    """
    metrics = metrics or RefreshMetrics()
    os.makedirs(_DB_DIR, exist_ok=True)

    static_age = _age(static_db.static_refreshed_at(STATIC_DB_PATH))
    rt_age = _age(rt_db.rt_refreshed_at(RT_DB_PATH))

    static_stale = static_age is None or static_age >= _STATIC_TTL
    rt_stale = rt_age is None or rt_age >= _RT_TTL

    if not static_stale and not rt_stale:
        metrics.cache_hit = True
        metrics.final_static_db_bytes = _size(STATIC_DB_PATH)
        metrics.final_rt_db_bytes = _size(RT_DB_PATH)
        return RefreshOutcome(False, False, rt_age, static_age, metrics)

    # Download outside the lock — errors propagate to the caller.
    zip_bytes = _download_static_data(metrics) if static_stale else None
    tu_bytes, alerts_bytes = _download_rt_data(metrics) if rt_stale else (None, None)

    refreshed_static = False
    refreshed_rt = False
    wait_start = time.monotonic()
    with _refresh_lock():
        metrics.waited_for_lock_seconds = time.monotonic() - wait_start
        # Re-check under lock — a sibling may have just refreshed while we downloaded.
        static_age = _age(static_db.static_refreshed_at(STATIC_DB_PATH))
        rt_age = _age(rt_db.rt_refreshed_at(RT_DB_PATH))
        static_stale = static_age is None or static_age >= _STATIC_TTL
        rt_stale = rt_age is None or rt_age >= _RT_TTL

        if static_stale and zip_bytes is not None:
            _build_static(zip_bytes, metrics)
            refreshed_static = True
            static_age = 0
        if rt_stale and tu_bytes is not None:
            _build_rt(tu_bytes, alerts_bytes, metrics)
            refreshed_rt = True
            rt_age = 0

    metrics.final_static_db_bytes = _size(STATIC_DB_PATH)
    metrics.final_rt_db_bytes = _size(RT_DB_PATH)
    return RefreshOutcome(refreshed_rt, refreshed_static, rt_age, static_age, metrics)


def refresh_static(metrics: RefreshMetrics | None = None) -> RefreshMetrics:
    """Force a static GTFS rebuild without holding the cross-process lock.

    Prefer refresh_static_locked for cron/script use so concurrent CGI builds
    are serialized.
    """
    metrics = metrics or RefreshMetrics()
    os.makedirs(_DB_DIR, exist_ok=True)
    zip_bytes = _download_static_data(metrics)
    _build_static(zip_bytes, metrics)
    return metrics


def refresh_static_locked(metrics: RefreshMetrics | None = None) -> RefreshMetrics:
    """Force a static GTFS rebuild, holding the cross-process lock around the build.

    Intended for cron/script callers. Downloads outside the lock; only the
    expensive DB build step is serialized so concurrent CGI processes are not
    blocked during the download.
    """
    metrics = metrics or RefreshMetrics()
    os.makedirs(_DB_DIR, exist_ok=True)
    zip_bytes = _download_static_data(metrics)
    with _refresh_lock():
        _build_static(zip_bytes, metrics)
    return metrics


def _download_static_data(metrics: RefreshMetrics) -> bytes:
    res = download.download_static_zip()
    metrics.static_download_bytes = res.size
    metrics.static_download_seconds = res.elapsed_s
    return res.bytes_


def _build_static(zip_bytes: bytes, metrics: RefreshMetrics) -> None:
    build = static_db.build_static_db(zip_bytes, STATIC_DB_PATH)
    metrics.static_rows = dict(build.rows_per_table)
    metrics.static_parse_seconds = dict(build.parse_seconds_per_table)
    metrics.static_write_seconds = build.write_seconds_total
    metrics.final_static_db_bytes = build.final_db_bytes


def _download_rt_data(metrics: RefreshMetrics) -> tuple[bytes, bytes | None]:
    tu = download.download_tripupdates()
    metrics.rt_download_bytes = tu.size
    metrics.rt_download_seconds = tu.elapsed_s
    try:
        al = download.download_servicealerts()
        metrics.alerts_download_bytes = al.size
        metrics.alerts_download_seconds = al.elapsed_s
        alerts_bytes = al.bytes_
    except download.FiveElevenError:
        alerts_bytes = None
    return tu.bytes_, alerts_bytes


def _build_rt(tu_bytes: bytes, alerts_bytes: bytes | None, metrics: RefreshMetrics) -> None:
    build = rt_db.build_rt_db(tu_bytes, alerts_bytes, RT_DB_PATH)
    metrics.rt_parse_seconds = build.parse_seconds
    metrics.rt_write_seconds = build.write_seconds
    metrics.rt_rows = build.rt_rows
    metrics.alert_rows = build.alert_rows
    metrics.final_rt_db_bytes = build.final_db_bytes


def lookup_departures(
    feed_stop_keys: Iterable[tuple[str, str]],
    next_seconds: int,
    now_utc: int | None = None,
) -> dict:
    """Return ``{<feed_onestop_id+stop_id>: {"departures": [...]}}`` keyed by
    the inputs. Each value is in the same shape as one element of
    ``proxy.get_departures_by_onestop_ids``'s per-stop result.

    Caller is responsible for having already called refresh_if_stale.
    """
    out: dict[tuple[str, str], dict] = {}
    if not os.path.isfile(STATIC_DB_PATH):
        raise RuntimeError("gtfs511 static DB missing — refresh_if_stale first")
    sdb = static_db.open_static_db(STATIC_DB_PATH)
    rdb = rt_db.open_rt_db(RT_DB_PATH) if os.path.isfile(RT_DB_PATH) else None
    try:
        for feed_id, stop_id in feed_stop_keys:
            meta = feed_mapping.metadata_for(feed_id)
            deps = lookup_mod.lookup_departures(
                static_db=sdb, rt_db=rdb,
                stop_id=stop_id, feed_metadata=meta,
                next_seconds=next_seconds, now_utc=now_utc,
            )
            out[(feed_id, stop_id)] = {"departures": deps, "alerts": []}
    finally:
        sdb.close()
        if rdb is not None:
            rdb.close()
    return out


# ─── helpers ──────────────────────────────────────────────────────────────────


def _age(t: int | None) -> int | None:
    if t is None:
        return None
    return max(0, int(time.time()) - t)


def _size(path: str) -> int:
    try:
        return os.path.getsize(path)
    except OSError:
        return 0


class _refresh_lock:
    """File-backed mutex using SQLite BEGIN EXCLUSIVE on a dedicated DB.

    Survives multiple CGI processes. Released on context exit.
    """
    def __init__(self) -> None:
        self._db: sqlite3.Connection | None = None

    def __enter__(self):
        os.makedirs(os.path.dirname(_LOCK_DB_PATH) or ".", exist_ok=True)
        # ~60s should be ample for a static rebuild on local hw; tighter than that
        # risks two CGI workers racing on a slow link.
        self._db = sqlite3.connect(_LOCK_DB_PATH, timeout=60)
        self._db.execute("CREATE TABLE IF NOT EXISTS m (x INTEGER)")
        self._db.execute("BEGIN EXCLUSIVE")
        return self

    def __exit__(self, exc_type, exc, tb):
        if self._db is not None:
            try:
                self._db.execute("COMMIT")
            except sqlite3.Error:
                pass
            self._db.close()
            self._db = None
