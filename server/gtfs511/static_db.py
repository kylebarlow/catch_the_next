"""Parse a 511 static GTFS bundle into a local SQLite file.

We only ingest the tables we need for departure lookup:

    agency, routes, stops, trips, stop_times, calendar, calendar_dates,
    feed_info (when present), and a small `feed_meta` row carrying the
    refresh timestamp.

Each refresh rebuilds the file from scratch into a temp path, then atomically
renames over the live file — this avoids partial-state reads from concurrent
proxy requests.
"""

import csv
import io
import os
import sqlite3
import tempfile
import time
import zipfile
from dataclasses import dataclass
from datetime import date, timedelta

# Tables we ingest. ``required`` means the build fails if the file is missing.
# Each entry: (gtfs_filename, table_name, required, columns)
#
# stop_times: arrival_time, pickup_type, drop_off_type omitted (unused by
# lookup.py).  departure_time is stored as INTEGER seconds since service-day
# midnight (converted during ingest).  Rows are also pruned to the active
# service window — see _compute_active_trip_ids().
_TABLES = [
    ("agency.txt",         "agency",         True,  ["agency_id", "agency_name", "agency_url", "agency_timezone"]),
    ("routes.txt",         "routes",         True,  ["route_id", "agency_id", "route_short_name", "route_long_name", "route_type"]),
    ("stops.txt",          "stops",          True,  ["stop_id", "stop_code", "stop_name", "stop_lat", "stop_lon", "parent_station", "location_type"]),
    ("trips.txt",          "trips",          True,  ["trip_id", "route_id", "service_id", "trip_headsign", "direction_id", "block_id", "shape_id"]),
    ("stop_times.txt",     "stop_times",     True,  ["trip_id", "departure_time", "stop_id", "stop_sequence"]),
    ("calendar.txt",       "calendar",       False, ["service_id", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday", "start_date", "end_date"]),
    ("calendar_dates.txt", "calendar_dates", False, ["service_id", "date", "exception_type"]),
]


@dataclass
class StaticBuildMetrics:
    bytes_input: int
    parse_seconds_per_table: dict
    rows_per_table: dict
    write_seconds_total: float
    final_db_bytes: int
    sqlite_path: str


def _ddl_for(
    table: str, cols: list[str], integer_cols: frozenset[str] = frozenset()
) -> str:
    col_defs = ", ".join(
        f'"{c}" INTEGER' if c in integer_cols else f'"{c}" TEXT'
        for c in cols
    )
    return f'CREATE TABLE "{table}" ({col_defs})'


def _gtfs_time_to_secs(s: str | None) -> int | None:
    """Convert GTFS HH:MM:SS (may exceed 24h) to integer seconds since midnight."""
    if not s:
        return None
    try:
        h, m, sec = s.split(":")
        return int(h) * 3600 + int(m) * 60 + int(sec)
    except (ValueError, AttributeError):
        return None


def _compute_active_trip_ids(
    zf: zipfile.ZipFile, available: set[str]
) -> frozenset[str] | None:
    """Return trip_ids whose service overlaps [today-1, today+7], or None to skip.

    Returns None when calendar data is absent or unparseable — caller should
    ingest all stop_times without filtering.  Returns an empty frozenset when
    calendar data exists but nothing falls in the window (legitimate gap).
    """
    if "trips.txt" not in available:
        return None
    if "calendar.txt" not in available and "calendar_dates.txt" not in available:
        return None

    window_start = (date.today() - timedelta(days=1)).strftime("%Y%m%d")
    window_end = (date.today() + timedelta(days=7)).strftime("%Y%m%d")
    active_sids: set[str] = set()

    if "calendar.txt" in available:
        rows = list(_read_csv(zf, "calendar.txt"))
        if rows:
            hdr = rows[0]
            try:
                si = hdr.index("service_id")
                sti = hdr.index("start_date")
                ei = hdr.index("end_date")
                for row in rows[1:]:
                    if len(row) > max(si, sti, ei):
                        if row[sti] <= window_end and row[ei] >= window_start:
                            active_sids.add(row[si])
            except ValueError:
                pass

    if "calendar_dates.txt" in available:
        rows = list(_read_csv(zf, "calendar_dates.txt"))
        if rows:
            hdr = rows[0]
            try:
                si = hdr.index("service_id")
                di = hdr.index("date")
                xi = hdr.index("exception_type")
                for row in rows[1:]:
                    if len(row) > max(si, di, xi):
                        if row[xi] == "1" and window_start <= row[di] <= window_end:
                            active_sids.add(row[si])
            except ValueError:
                pass

    trip_ids: set[str] = set()
    rows = list(_read_csv(zf, "trips.txt"))
    if not rows:
        return None
    hdr = rows[0]
    try:
        ti = hdr.index("trip_id")
        si = hdr.index("service_id")
        for row in rows[1:]:
            if len(row) > max(ti, si) and row[si] in active_sids:
                trip_ids.add(row[ti])
    except ValueError:
        return None

    return frozenset(trip_ids)


def _read_csv(zf: zipfile.ZipFile, filename: str):
    """Yield rows from a GTFS .txt member.

    GTFS uses UTF-8 with optional BOM; we strip it via utf-8-sig.
    """
    with zf.open(filename) as raw:
        text = io.TextIOWrapper(raw, encoding="utf-8-sig", newline="")
        reader = csv.reader(text)
        try:
            header = next(reader)
        except StopIteration:
            return
        yield header
        for row in reader:
            yield row


def build_static_db(zip_bytes: bytes, target_path: str) -> StaticBuildMetrics:
    """Build a fresh SQLite file at *target_path* from the GTFS zip bytes.

    Writes to a sibling temp file then renames atomically.
    """
    metrics = StaticBuildMetrics(
        bytes_input=len(zip_bytes),
        parse_seconds_per_table={},
        rows_per_table={},
        write_seconds_total=0.0,
        final_db_bytes=0,
        sqlite_path=target_path,
    )

    parent_dir = os.path.dirname(target_path) or "."
    os.makedirs(parent_dir, exist_ok=True)

    # tempfile.mkstemp returns an open fd we don't need; close it.
    fd, tmp_path = tempfile.mkstemp(prefix=".gtfs_static.", dir=parent_dir)
    os.close(fd)

    overall_write_start = time.monotonic()
    try:
        # Force a brand-new file; mkstemp already created an empty one.
        os.unlink(tmp_path)
        db = sqlite3.connect(tmp_path)
        try:
            db.execute("PRAGMA journal_mode=OFF")
            db.execute("PRAGMA synchronous=OFF")
            db.execute("PRAGMA temp_store=MEMORY")

            zf = zipfile.ZipFile(io.BytesIO(zip_bytes))
            available = set(zf.namelist())

            # Pre-compute active trip_ids for service-window pruning.
            # None means "skip filter"; frozenset means "keep only these".
            active_trip_ids = _compute_active_trip_ids(zf, available)

            for filename, table, required, cols in _TABLES:
                if filename not in available:
                    if required:
                        raise ValueError(f"static GTFS missing required {filename!r}")
                    continue

                t0 = time.monotonic()
                rows_inserted = 0
                integer_cols = (
                    frozenset({"departure_time"}) if table == "stop_times" else frozenset()
                )
                db.execute(_ddl_for(table, cols, integer_cols))
                placeholders = ",".join("?" * len(cols))
                insert_sql = f'INSERT INTO "{table}" VALUES ({placeholders})'

                # stop_times: column positions for filter + time conversion.
                _st_trip_col = cols.index("trip_id") if table == "stop_times" else -1
                _st_dep_col = (
                    cols.index("departure_time")
                    if table == "stop_times" and "departure_time" in cols
                    else -1
                )

                with db:
                    batch: list[tuple] = []
                    header = None
                    col_index: list[int] = []
                    for row in _read_csv(zf, filename):
                        if header is None:
                            header = row
                            # Resolve column positions; missing columns -> None.
                            col_index = [
                                header.index(c) if c in header else -1
                                for c in cols
                            ]
                            continue
                        values = tuple(
                            (row[i] if 0 <= i < len(row) else None) for i in col_index
                        )
                        if table == "stop_times":
                            # Drop rows outside the service window.
                            if (
                                active_trip_ids is not None
                                and values[_st_trip_col] not in active_trip_ids
                            ):
                                continue
                            # Convert departure_time to integer seconds.
                            if _st_dep_col >= 0:
                                vlist = list(values)
                                vlist[_st_dep_col] = _gtfs_time_to_secs(
                                    vlist[_st_dep_col]
                                )
                                values = tuple(vlist)
                        batch.append(values)
                        if len(batch) >= 5000:
                            db.executemany(insert_sql, batch)
                            rows_inserted += len(batch)
                            batch.clear()
                    if batch:
                        db.executemany(insert_sql, batch)
                        rows_inserted += len(batch)

                metrics.rows_per_table[table] = rows_inserted
                metrics.parse_seconds_per_table[table] = time.monotonic() - t0

            # Indexes after bulk insert. The PK-like indexes (trips.trip_id,
            # routes.route_id, agency.agency_id) are critical — without them
            # every stop_times → trips → routes JOIN does full scans, which
            # caused ~1s per stop on a 3.5M-row feed.
            db.execute('CREATE INDEX idx_stop_times_stop ON stop_times(stop_id)')
            db.execute('CREATE INDEX idx_stop_times_trip ON stop_times(trip_id, stop_sequence)')
            db.execute('CREATE INDEX idx_trips_trip_id ON trips(trip_id)')
            db.execute('CREATE INDEX idx_trips_service ON trips(service_id)')
            db.execute('CREATE INDEX idx_routes_route_id ON routes(route_id)')
            db.execute('CREATE INDEX idx_agency_id ON agency(agency_id)')
            db.execute('CREATE INDEX idx_stops_stop_id ON stops(stop_id)')
            db.execute('CREATE INDEX idx_stops_parent ON stops(parent_station)')
            if "calendar_dates" in metrics.rows_per_table:
                db.execute('CREATE INDEX idx_caldates ON calendar_dates(service_id, date)')
            db.execute('ANALYZE')

            # feed_meta carries refresh state inspected by refresh_if_stale().
            db.execute('CREATE TABLE feed_meta (key TEXT PRIMARY KEY, value TEXT)')
            db.executemany(
                'INSERT INTO feed_meta VALUES (?, ?)',
                [
                    ("refreshed_at", str(int(time.time()))),
                    ("source_bytes", str(len(zip_bytes))),
                ],
            )
            db.commit()
            db.execute("VACUUM")
        finally:
            db.close()

        os.replace(tmp_path, target_path)
    except Exception:
        try:
            os.unlink(tmp_path)
        except FileNotFoundError:
            pass
        raise

    metrics.write_seconds_total = time.monotonic() - overall_write_start
    metrics.final_db_bytes = os.path.getsize(target_path)
    return metrics


def open_static_db(path: str) -> sqlite3.Connection:
    """Open the static DB for read-only queries."""
    db = sqlite3.connect(f"file:{path}?mode=ro", uri=True, timeout=5.0)
    db.row_factory = sqlite3.Row
    db.execute("PRAGMA busy_timeout = 5000")
    return db


def static_refreshed_at(path: str) -> int | None:
    """Return the UNIX timestamp of the last static build, or None."""
    if not os.path.isfile(path):
        return None
    try:
        with open_static_db(path) as db:
            row = db.execute(
                "SELECT value FROM feed_meta WHERE key = 'refreshed_at'"
            ).fetchone()
            return int(row[0]) if row else None
    except sqlite3.Error:
        return None
