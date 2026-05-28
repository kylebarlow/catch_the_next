"""Parse GTFS-Realtime TripUpdates (and optionally ServiceAlerts) into SQLite.

On each refresh we drop and recreate the realtime tables. We never accumulate
historic data — only the latest snapshot matters. The whole rebuild runs in
one transaction so concurrent readers either see the prior snapshot or the
new one (never a half-written mix).
"""

import os
import sqlite3
import tempfile
import time
from dataclasses import dataclass

from google.transit import gtfs_realtime_pb2  # type: ignore


@dataclass
class RtBuildMetrics:
    bytes_input_tripupdates: int = 0
    bytes_input_alerts: int = 0
    parse_seconds: float = 0.0
    write_seconds: float = 0.0
    final_db_bytes: int = 0
    sqlite_path: str = ""
    rt_rows: int = 0
    alert_rows: int = 0
    entities_skipped: int = 0


_DDL_RT = """
CREATE TABLE rt_trip_stop_times (
    trip_id              TEXT NOT NULL,
    stop_id              TEXT NOT NULL,
    stop_sequence        INTEGER,
    route_id             TEXT,
    start_date           TEXT,
    schedule_relationship TEXT,
    scheduled_utc        INTEGER,
    predicted_utc        INTEGER,
    delay_seconds        INTEGER,
    fetched_at           INTEGER NOT NULL
);
CREATE INDEX idx_rt_stop ON rt_trip_stop_times (stop_id, predicted_utc);
CREATE INDEX idx_rt_trip ON rt_trip_stop_times (trip_id, stop_sequence);

CREATE TABLE rt_alerts (
    alert_id    TEXT,
    cause       TEXT,
    effect      TEXT,
    severity    TEXT,
    header      TEXT,
    description TEXT,
    url         TEXT,
    active_periods TEXT,
    informed_entities TEXT,
    fetched_at  INTEGER NOT NULL
);
CREATE INDEX idx_alert_id ON rt_alerts (alert_id);

CREATE TABLE rt_meta (key TEXT PRIMARY KEY, value TEXT);
"""



def build_rt_db(
    tripupdates_bytes: bytes,
    alerts_bytes: bytes | None,
    target_path: str,
) -> RtBuildMetrics:
    metrics = RtBuildMetrics(
        bytes_input_tripupdates=len(tripupdates_bytes),
        bytes_input_alerts=len(alerts_bytes or b""),
        sqlite_path=target_path,
    )

    parse_start = time.monotonic()
    tu_feed = gtfs_realtime_pb2.FeedMessage()
    tu_feed.ParseFromString(tripupdates_bytes)

    al_feed = None
    if alerts_bytes:
        al_feed = gtfs_realtime_pb2.FeedMessage()
        al_feed.ParseFromString(alerts_bytes)

    fetched_at = int(time.time())
    rt_rows: list[tuple] = []
    skipped = 0
    for entity in tu_feed.entity:
        if not entity.HasField("trip_update"):
            continue
        tu = entity.trip_update
        trip = tu.trip
        trip_id = trip.trip_id or ""
        if not trip_id:
            skipped += 1
            continue
        route_id = trip.route_id or None
        start_date = trip.start_date or None
        sched_rel_num = trip.schedule_relationship if trip.HasField("schedule_relationship") else 0
        sched_rel = _schedule_relationship_name(sched_rel_num)

        for stu in tu.stop_time_update:
            stop_id = stu.stop_id or ""
            if not stop_id:
                skipped += 1
                continue
            stop_seq = stu.stop_sequence if stu.HasField("stop_sequence") else None

            # Prefer departure event; fall back to arrival.
            ev = stu.departure if stu.HasField("departure") else (stu.arrival if stu.HasField("arrival") else None)
            predicted = ev.time if (ev is not None and ev.HasField("time") and ev.time) else None
            delay = ev.delay if (ev is not None and ev.HasField("delay")) else None
            scheduled = None
            if predicted is not None and delay is not None:
                scheduled = predicted - delay

            stu_rel_num = stu.schedule_relationship if stu.HasField("schedule_relationship") else 0
            stu_rel = _stu_schedule_relationship_name(stu_rel_num) or sched_rel

            rt_rows.append((
                trip_id, stop_id, stop_seq, route_id, start_date,
                stu_rel, scheduled, predicted, delay, fetched_at,
            ))

    alert_rows: list[tuple] = []
    if al_feed is not None:
        import json as _json
        for entity in al_feed.entity:
            if not entity.HasField("alert"):
                continue
            a = entity.alert
            header = _translation(a.header_text)
            description = _translation(a.description_text)
            url = _translation(a.url)
            cause = gtfs_realtime_pb2.Alert.Cause.Name(a.cause) if a.cause else None
            effect = gtfs_realtime_pb2.Alert.Effect.Name(a.effect) if a.effect else None
            severity = (
                gtfs_realtime_pb2.Alert.SeverityLevel.Name(a.severity_level)
                if a.HasField("severity_level") else None
            )
            periods = [{"start": p.start or None, "end": p.end or None} for p in a.active_period]
            informed = [
                {
                    "agency_id": s.agency_id or None,
                    "route_id": s.route_id or None,
                    "stop_id": s.stop_id or None,
                    "trip_id": (s.trip.trip_id or None) if s.HasField("trip") else None,
                }
                for s in a.informed_entity
            ]
            alert_rows.append((
                entity.id or None,
                cause, effect, severity,
                header, description, url,
                _json.dumps(periods, separators=(",", ":")),
                _json.dumps(informed, separators=(",", ":")),
                fetched_at,
            ))
    metrics.parse_seconds = time.monotonic() - parse_start

    write_start = time.monotonic()
    parent_dir = os.path.dirname(target_path) or "."
    os.makedirs(parent_dir, exist_ok=True)
    fd, tmp_path = tempfile.mkstemp(prefix=".gtfs_rt.", dir=parent_dir)
    os.close(fd)
    os.unlink(tmp_path)
    try:
        db = sqlite3.connect(tmp_path)
        try:
            db.execute("PRAGMA journal_mode=OFF")
            db.execute("PRAGMA synchronous=OFF")
            db.execute("PRAGMA temp_store=MEMORY")
            with db:
                for stmt in _DDL_RT.strip().split(";"):
                    s = stmt.strip()
                    if s:
                        db.execute(s)
                db.executemany(
                    "INSERT INTO rt_trip_stop_times VALUES (?,?,?,?,?,?,?,?,?,?)",
                    rt_rows,
                )
                db.executemany(
                    "INSERT INTO rt_alerts VALUES (?,?,?,?,?,?,?,?,?,?)",
                    alert_rows,
                )
                db.executemany(
                    "INSERT INTO rt_meta VALUES (?, ?)",
                    [
                        ("refreshed_at", str(fetched_at)),
                        ("rt_rows", str(len(rt_rows))),
                        ("alert_rows", str(len(alert_rows))),
                    ],
                )
        finally:
            db.close()
        os.replace(tmp_path, target_path)
    except Exception:
        try:
            os.unlink(tmp_path)
        except FileNotFoundError:
            pass
        raise

    metrics.write_seconds = time.monotonic() - write_start
    metrics.rt_rows = len(rt_rows)
    metrics.alert_rows = len(alert_rows)
    metrics.entities_skipped = skipped
    metrics.final_db_bytes = os.path.getsize(target_path)
    return metrics


def open_rt_db(path: str) -> sqlite3.Connection:
    db = sqlite3.connect(f"file:{path}?mode=ro", uri=True, timeout=5.0)
    db.row_factory = sqlite3.Row
    db.execute("PRAGMA busy_timeout = 5000")
    return db


def rt_refreshed_at(path: str) -> int | None:
    if not os.path.isfile(path):
        return None
    try:
        with open_rt_db(path) as db:
            row = db.execute(
                "SELECT value FROM rt_meta WHERE key = 'refreshed_at'"
            ).fetchone()
            return int(row[0]) if row else None
    except sqlite3.Error:
        return None


def _translation(translated_string) -> str | None:
    """Return the en translation if present, else the first non-empty."""
    if translated_string is None:
        return None
    en = None
    first = None
    for t in translated_string.translation:
        if first is None and t.text:
            first = t.text
        if (t.language or "").lower().startswith("en") and t.text:
            en = t.text
            break
    return en or first


def _schedule_relationship_name(value: int) -> str:
    # TripDescriptor.ScheduleRelationship: SCHEDULED, ADDED, UNSCHEDULED, CANCELED, REPLACEMENT, DUPLICATED, DELETED
    try:
        return gtfs_realtime_pb2.TripDescriptor.ScheduleRelationship.Name(value)
    except Exception:
        return "SCHEDULED"


def _stu_schedule_relationship_name(value: int) -> str | None:
    # StopTimeUpdate.ScheduleRelationship: SCHEDULED, SKIPPED, NO_DATA, UNSCHEDULED
    try:
        return gtfs_realtime_pb2.TripUpdate.StopTimeUpdate.ScheduleRelationship.Name(value)
    except Exception:
        return None
