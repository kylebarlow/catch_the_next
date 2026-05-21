"""Departure lookup against the 511 SQLite caches.

Produces records in the **exact field set** returned by
``proxy._shape_departures`` (19 fields) so downstream serialization is
agnostic about the source.

Two passes are merged + deduped per trip_id:

    1. RT pass    -- joins rt_trip_stop_times to static trips/routes/stops/agency.
    2. SCHED pass -- resolves today's stop_times via calendar/calendar_dates
                     (service-day aware so post-midnight trips show on the
                     prior service day's calendar row), used to fill in
                     departures the RT feed didn't push.
"""

import sqlite3
from datetime import datetime, timedelta, timezone
from zoneinfo import ZoneInfo

# Field names that proxy._shape_departures emits. Keep this in lock-step.
_DEPARTURE_FIELDS = (
    "route_short_name", "headsign",
    "scheduled_departure_time", "scheduled_departure_utc", "scheduled_departure_minutes",
    "live_departure_time", "live_departure_utc", "live_departure_minutes",
    "time_source", "schedule_relationship",
    "agency_name",
    "feed_onestop_id", "feed_name",
    "attribution_text", "attribution_instructions", "use_without_attribution",
    "license_spdx", "license_url",
)


def lookup_departures(
    static_db: sqlite3.Connection,
    rt_db: sqlite3.Connection | None,
    stop_id: str,
    feed_metadata: dict,
    next_seconds: int,
    now_utc: int | None = None,
) -> list[dict]:
    """Return Transitland-shape departure records for one GTFS stop_id.

    *feed_metadata* carries the attribution fields for the agency this stop
    belongs to — `feed_mapping.metadata_for(...)` produces it.

    *static_db* is required; *rt_db* may be None (returns scheduled-only).
    """
    now_utc = now_utc if now_utc is not None else int(datetime.now(tz=timezone.utc).timestamp())
    end_utc = now_utc + next_seconds

    # Expand parent stations to their child platforms; many feeds put RT on
    # children but stop discovery returns the parent.
    stop_ids = _resolve_stop_id_group(static_db, stop_id)

    agency_tz_str = _agency_timezone(static_db) or "America/Los_Angeles"

    rt_records: dict[tuple, dict] = {}
    if rt_db is not None:
        for rec in _rt_pass(rt_db, static_db, stop_ids, now_utc, end_utc, feed_metadata):
            key = _dedup_key(rec)
            rt_records[key] = rec

    sched_records: list[dict] = []
    for rec in _sched_pass(static_db, stop_ids, now_utc, end_utc, agency_tz_str, feed_metadata):
        key = _dedup_key(rec)
        if key in rt_records:
            # RT version already covers this; backfill scheduled fields
            # if RT didn't compute them from delay.
            rt = rt_records[key]
            if rt["scheduled_departure_utc"] is None:
                rt["scheduled_departure_utc"] = rec["scheduled_departure_utc"]
                rt["scheduled_departure_time"] = rec["scheduled_departure_time"]
                rt["scheduled_departure_minutes"] = rec["scheduled_departure_minutes"]
            continue
        sched_records.append(rec)

    out = list(rt_records.values()) + sched_records
    out.sort(key=_sort_key)
    # Strip the internal-only fields before returning.
    for r in out:
        r.pop("_trip_id", None)
    return out


# ─── helpers ──────────────────────────────────────────────────────────────────


def _resolve_stop_id_group(static_db: sqlite3.Connection, stop_id: str) -> list[str]:
    rows = static_db.execute(
        "SELECT stop_id FROM stops WHERE stop_id = ? OR parent_station = ?",
        (stop_id, stop_id),
    ).fetchall()
    ids = [r[0] for r in rows]
    return ids or [stop_id]


def _agency_timezone(static_db: sqlite3.Connection) -> str | None:
    row = static_db.execute(
        "SELECT agency_timezone FROM agency WHERE agency_timezone IS NOT NULL "
        "AND agency_timezone != '' LIMIT 1"
    ).fetchone()
    return row[0] if row else None


def _build_record(
    *, route_short_name, headsign, scheduled_utc, predicted_utc, schedule_relationship,
    agency_name, feed_metadata, now_utc, trip_id,
) -> dict:
    time_source = "LIVE" if predicted_utc and schedule_relationship != "SKIPPED" else "SCHEDULED"
    sched_local_iso = _to_iso_utc(scheduled_utc)
    live_local_iso = _to_iso_utc(predicted_utc)
    rec = {
        "route_short_name": route_short_name or "",
        "headsign": headsign or "",
        "scheduled_departure_time": sched_local_iso,
        "scheduled_departure_utc": sched_local_iso,
        "scheduled_departure_minutes": _minutes_from(scheduled_utc, now_utc),
        "live_departure_time": live_local_iso,
        "live_departure_utc": live_local_iso,
        "live_departure_minutes": _minutes_from(predicted_utc, now_utc),
        "time_source": time_source,
        "schedule_relationship": schedule_relationship or "SCHEDULED",
        "agency_name": agency_name,
        "feed_onestop_id": feed_metadata.get("feed_onestop_id"),
        "feed_name": feed_metadata.get("feed_name"),
        "attribution_text": feed_metadata.get("attribution_text"),
        "attribution_instructions": feed_metadata.get("attribution_instructions"),
        "use_without_attribution": bool(feed_metadata.get("use_without_attribution")),
        "license_spdx": feed_metadata.get("license_spdx"),
        "license_url": feed_metadata.get("license_url"),
        "_trip_id": trip_id,
    }
    return rec


def _rt_pass(rt_db, static_db, stop_ids, now_utc, end_utc, feed_metadata):
    placeholders = ",".join("?" * len(stop_ids))
    rows = rt_db.execute(
        f"""SELECT trip_id, stop_id, stop_sequence, route_id,
                   schedule_relationship, scheduled_utc, predicted_utc
            FROM rt_trip_stop_times
            WHERE stop_id IN ({placeholders})
              AND (
                (predicted_utc IS NOT NULL AND predicted_utc BETWEEN ? AND ?)
                OR (scheduled_utc IS NOT NULL AND scheduled_utc BETWEEN ? AND ?)
              )""",
        (*stop_ids, now_utc, end_utc, now_utc, end_utc),
    ).fetchall()
    if not rows:
        return

    # Hydrate trip_id → route metadata, agency in a single batch.
    trip_ids = {r["trip_id"] for r in rows}
    trip_meta = _fetch_trip_meta(static_db, trip_ids)
    agency_by_id = _fetch_agencies(static_db)

    for r in rows:
        meta = trip_meta.get(r["trip_id"], {})
        agency_name = agency_by_id.get(meta.get("agency_id")) if meta.get("agency_id") else None
        yield _build_record(
            route_short_name=meta.get("route_short_name"),
            headsign=meta.get("stop_headsign_by_seq", {}).get(r["stop_sequence"]) or meta.get("trip_headsign"),
            scheduled_utc=r["scheduled_utc"],
            predicted_utc=r["predicted_utc"],
            schedule_relationship=r["schedule_relationship"],
            agency_name=agency_name,
            feed_metadata=feed_metadata,
            now_utc=now_utc,
            trip_id=r["trip_id"],
        )


def _sched_pass(static_db, stop_ids, now_utc, end_utc, tz_str, feed_metadata):
    tz = ZoneInfo(tz_str)
    now_local = datetime.fromtimestamp(now_utc, tz=tz)

    # Trips that began on yesterday's service date can still depart "today"
    # (e.g. departure_time = 25:30:00 means 01:30 the next day). Today and
    # tomorrow cover the standard window.
    service_dates = [now_local.date() + timedelta(days=d) for d in (-1, 0, 1)]

    placeholders_stops = ",".join("?" * len(stop_ids))
    agency_by_id = _fetch_agencies(static_db)
    weekday_cols = ("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")

    for service_date in service_dates:
        date_str = service_date.strftime("%Y%m%d")
        day_col = weekday_cols[service_date.weekday()]

        active = set()
        try:
            cal_rows = static_db.execute(
                f"SELECT service_id FROM calendar WHERE \"{day_col}\" = '1' "
                "AND start_date <= ? AND end_date >= ?",
                (date_str, date_str),
            ).fetchall()
            active.update(r[0] for r in cal_rows)
        except sqlite3.OperationalError:
            pass  # calendar.txt absent — only calendar_dates in use
        try:
            for sid, ex in static_db.execute(
                "SELECT service_id, exception_type FROM calendar_dates WHERE date = ?",
                (date_str,),
            ):
                if ex == "1":
                    active.add(sid)
                elif ex == "2":
                    active.discard(sid)
        except sqlite3.OperationalError:
            pass

        if not active:
            continue

        midnight_local = datetime.combine(service_date, datetime.min.time()).replace(tzinfo=tz)
        midnight_utc = int(midnight_local.timestamp())

        sid_placeholders = ",".join("?" * len(active))
        rows = static_db.execute(
            f"""SELECT st.trip_id, st.departure_time, st.stop_sequence, st.stop_id,
                       t.route_id, t.trip_headsign, r.route_short_name, r.agency_id
                FROM stop_times st
                JOIN trips t ON t.trip_id = st.trip_id
                JOIN routes r ON r.route_id = t.route_id
                WHERE st.stop_id IN ({placeholders_stops})
                  AND t.service_id IN ({sid_placeholders})""",
            (*stop_ids, *active),
        ).fetchall()

        for r in rows:
            secs = _parse_gtfs_time(r["departure_time"])
            if secs is None:
                continue
            abs_utc = midnight_utc + secs
            if not (now_utc <= abs_utc <= end_utc):
                continue
            yield _build_record(
                route_short_name=r["route_short_name"],
                headsign=r["trip_headsign"],
                scheduled_utc=abs_utc,
                predicted_utc=None,
                schedule_relationship="SCHEDULED",
                agency_name=agency_by_id.get(r["agency_id"]),
                feed_metadata=feed_metadata,
                now_utc=now_utc,
                trip_id=r["trip_id"],
            )


def _fetch_trip_meta(static_db, trip_ids: set[str]) -> dict:
    if not trip_ids:
        return {}
    trip_list = list(trip_ids)
    chunk = 800
    out = {}
    for i in range(0, len(trip_list), chunk):
        part = trip_list[i:i + chunk]
        placeholders = ",".join("?" * len(part))
        for r in static_db.execute(
            f"""SELECT t.trip_id, t.trip_headsign, r.route_short_name, r.agency_id
                FROM trips t JOIN routes r ON r.route_id = t.route_id
                WHERE t.trip_id IN ({placeholders})""",
            part,
        ):
            out[r["trip_id"]] = {
                "trip_headsign": r["trip_headsign"],
                "route_short_name": r["route_short_name"],
                "agency_id": r["agency_id"],
                "stop_headsign_by_seq": {},
            }
    return out


def _fetch_agencies(static_db) -> dict:
    return {r["agency_id"]: r["agency_name"] for r in static_db.execute(
        "SELECT agency_id, agency_name FROM agency"
    )}


def _parse_gtfs_time(s) -> int | None:
    """Parse GTFS departure_time to seconds since service-day midnight.

    Accepts either a pre-converted integer (stored in the DB) or an
    HH:MM:SS string (may exceed 24h for post-midnight trips).
    """
    if s is None:
        return None
    if isinstance(s, int):
        return s
    try:
        h, m, sec = s.split(":")
        return int(h) * 3600 + int(m) * 60 + int(sec)
    except (ValueError, AttributeError):
        return None


def _to_iso_utc(epoch_seconds: int | None) -> str | None:
    if epoch_seconds is None:
        return None
    return datetime.fromtimestamp(epoch_seconds, tz=timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def _minutes_from(epoch_seconds: int | None, now_utc: int) -> int | None:
    if epoch_seconds is None:
        return None
    return int((epoch_seconds - now_utc) // 60)


def _sort_key(rec: dict) -> int:
    if rec["time_source"] == "LIVE" and rec["live_departure_minutes"] is not None:
        return rec["live_departure_minutes"]
    return rec["scheduled_departure_minutes"] if rec["scheduled_departure_minutes"] is not None else 0


def _dedup_key(rec: dict) -> str | None:
    # RT and static stop_times can disagree on stop_id (parent vs child
    # platform) and stop_sequence (NULL in many 511 RT feeds). trip_id is the
    # canonical join key per the GTFS-RT spec for SCHEDULED trips; the lookup
    # is already scoped to a single parent-station group so trip_id uniquely
    # identifies a trip's visit to that station on non-loop routes.
    return rec.get("_trip_id")
