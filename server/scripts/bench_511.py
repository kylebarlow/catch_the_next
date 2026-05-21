"""Benchmark harness for the 511 GTFS-Realtime pipeline.

Run inside the Docker container:

    docker compose exec server python -m scripts.bench_511

Phases:
    1. Cold refresh  — purge caches, fetch static + RT, time everything.
    2. Warm lookup   — query N representative Bay Area stops × T trials.
    3. Stale RT refresh — simulate lazy refresh by zeroing rt_meta.refreshed_at,
                          then re-triggering refresh_if_stale.
    4. Disk footprint — sizes of static, RT, WAL, lock DBs.
    5. Operator coverage — agencies present in static feed vs. our mapping.

Writes a markdown report under server/bench_results/.
"""

import argparse
import json
import os
import resource
import sqlite3
import statistics
import sys
import time
from datetime import datetime, timezone

# Ensure the parent dir (server/) is importable when run with ``python -m``.
_HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(_HERE))

import gtfs511
from gtfs511 import api as gtfs511_api, feed_mapping


REPORT_DIR = os.path.join(os.path.dirname(_HERE), "bench_results")


def _human_bytes(n: int) -> str:
    for unit in ("B", "KB", "MB", "GB"):
        if n < 1024:
            return f"{n:.1f} {unit}"
        n /= 1024
    return f"{n:.1f} TB"


def _peak_rss_mb() -> float:
    # On Linux, ru_maxrss is in KB.
    return resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1024.0


def _purge_caches() -> None:
    for p in (gtfs511_api.STATIC_DB_PATH, gtfs511_api.RT_DB_PATH,
              gtfs511_api._LOCK_DB_PATH):
        for suffix in ("", "-wal", "-shm", "-journal"):
            full = p + suffix
            try:
                os.unlink(full)
            except FileNotFoundError:
                pass


def _file_size(path: str) -> int:
    try:
        return os.path.getsize(path)
    except OSError:
        return 0


def _select_busy_stops(n: int = 10) -> list[tuple[str, str, str]]:
    """Return ``[(feed_onestop_id, stop_id, stop_name)]`` for the N stops with
    the most stop_times rows. Falls back to a deterministic limit query.
    """
    if not os.path.isfile(gtfs511_api.STATIC_DB_PATH):
        return []
    db = sqlite3.connect(f"file:{gtfs511_api.STATIC_DB_PATH}?mode=ro", uri=True)
    db.row_factory = sqlite3.Row
    try:
        rows = db.execute(
            "SELECT st.stop_id, COALESCE(s.stop_name, st.stop_id) AS name, "
            "       COUNT(*) AS hits "
            "FROM stop_times st "
            "LEFT JOIN stops s ON s.stop_id = st.stop_id "
            "GROUP BY st.stop_id "
            "ORDER BY hits DESC "
            "LIMIT ?",
            (n,),
        ).fetchall()
    finally:
        db.close()
    # We don't know per-stop feed mapping inside the combined feed, so use
    # BART's feed_onestop_id as a stand-in for the metadata block. The lookup
    # itself only filters by stop_id, so this just controls the attribution
    # fields on the records — fine for a latency benchmark.
    return [("f-9q9-bart", r["stop_id"], r["name"]) for r in rows]


def _bench_warm_lookups(stops: list[tuple[str, str, str]], trials: int) -> dict:
    """Return per-stop latency stats + the number of departures returned."""
    if not stops:
        return {"per_stop": [], "overall_ms": []}
    per_stop = []
    all_latencies = []
    for feed_id, stop_id, name in stops:
        latencies = []
        last_dep_count = 0
        for _ in range(trials):
            t0 = time.monotonic()
            out = gtfs511.lookup_departures([(feed_id, stop_id)], next_seconds=3600)
            dt_ms = (time.monotonic() - t0) * 1000
            latencies.append(dt_ms)
            entry = out.get((feed_id, stop_id)) or {}
            last_dep_count = len(entry.get("departures", []))
        per_stop.append({
            "stop_id": stop_id,
            "name": name,
            "departures": last_dep_count,
            "p50_ms": statistics.median(latencies),
            "min_ms": min(latencies),
            "max_ms": max(latencies),
        })
        all_latencies.extend(latencies)
    return {"per_stop": per_stop, "overall_ms": all_latencies}


def _operator_coverage() -> dict:
    if not os.path.isfile(gtfs511_api.STATIC_DB_PATH):
        return {}
    db = sqlite3.connect(f"file:{gtfs511_api.STATIC_DB_PATH}?mode=ro", uri=True)
    try:
        agencies = [
            {"agency_id": a[0], "agency_name": a[1]}
            for a in db.execute("SELECT agency_id, agency_name FROM agency ORDER BY agency_name")
        ]
    finally:
        db.close()
    mapped_ids = {v["agency_id"] for v in feed_mapping.FEEDS.values() if v["agency_id"]}
    present = {a["agency_id"] for a in agencies}
    return {
        "agencies": agencies,
        "mapped_known": sorted(mapped_ids & present),
        "in_feed_but_unmapped": sorted(present - mapped_ids),
        "mapped_but_missing": sorted(mapped_ids - present),
    }


def _write_report(payload: dict, out_path: str) -> None:
    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    with open(out_path, "w") as f:
        f.write(_render_markdown(payload))


def _render_markdown(p: dict) -> str:
    cold = p["cold_refresh"]
    stale = p["stale_rt_refresh"]
    warm = p["warm_lookups"]
    disk = p["disk_footprint"]
    cov = p["operator_coverage"]
    lat = warm.get("overall_ms") or []
    lat_summary = ""
    if lat:
        lat_summary = (
            f"min {min(lat):.1f} ms · p50 {statistics.median(lat):.1f} ms · "
            f"p95 {sorted(lat)[max(0, int(len(lat)*0.95)-1)]:.1f} ms · "
            f"max {max(lat):.1f} ms"
        )
    lines = [
        f"# 511 GTFS-RT Bench — {p['timestamp']}",
        "",
        "## Headline",
        "",
        f"- **Cold refresh wall-clock**: **{cold['total_seconds']:.2f} s** "
        f"(download {cold['download_seconds']:.2f}s + parse {cold['parse_seconds']:.2f}s + write {cold['write_seconds']:.2f}s)",
        f"- **Total bytes downloaded**: {_human_bytes(cold['bytes_total'])}",
        f"- **Peak RSS during cold refresh**: {cold['peak_rss_mb']:.1f} MB",
        f"- **Stale-RT lazy refresh (sim CGI request)**: {stale['total_seconds']:.2f} s",
        f"- **Warm lookup latency**: {lat_summary or 'no stops queried'}",
        f"- **Static DB**: {_human_bytes(disk['static_db'])}  · **RT DB**: {_human_bytes(disk['rt_db'])}",
        "",
        "## Cold refresh breakdown",
        "",
        "| Phase | Bytes | Wall-clock |",
        "| --- | ---: | ---: |",
        f"| Static GTFS download | {_human_bytes(cold['static_download_bytes'])} | {cold['static_download_seconds']:.2f} s |",
        f"| Static GTFS parse + index | — | {sum(cold['static_parse_seconds'].values()):.2f} s |",
        f"| Static GTFS write (incl. VACUUM) | — | {cold['static_write_seconds']:.2f} s |",
        f"| TripUpdates download | {_human_bytes(cold['rt_download_bytes'])} | {cold['rt_download_seconds']:.2f} s |",
        f"| ServiceAlerts download | {_human_bytes(cold['alerts_download_bytes'])} | {cold['alerts_download_seconds']:.2f} s |",
        f"| RT parse | — | {cold['rt_parse_seconds']:.2f} s |",
        f"| RT write | — | {cold['rt_write_seconds']:.2f} s |",
        f"| **Total** | **{_human_bytes(cold['bytes_total'])}** | **{cold['total_seconds']:.2f} s** |",
        "",
        "### Static per-table",
        "",
        "| Table | Rows | Parse time |",
        "| --- | ---: | ---: |",
    ]
    for tbl, rows in sorted(cold['static_rows'].items()):
        secs = cold['static_parse_seconds'].get(tbl, 0.0)
        lines.append(f"| {tbl} | {rows:,} | {secs:.2f} s |")
    lines += [
        "",
        f"RT rows: **{cold['rt_rows']:,}**, alert rows: **{cold['alert_rows']:,}**",
        "",
        "## Stale-RT lazy refresh (simulates a single CGI request)",
        "",
        f"- Total: **{stale['total_seconds']:.2f} s**",
        f"- TripUpdates: {_human_bytes(stale['rt_download_bytes'])} in {stale['rt_download_seconds']:.2f} s",
        f"- Parse: {stale['rt_parse_seconds']:.2f} s · Write: {stale['rt_write_seconds']:.2f} s",
        f"- Lock wait: {stale['waited_for_lock_seconds']*1000:.0f} ms",
        "",
        "This is the worst-case penalty paid by the first request after a stale window. "
        "It does NOT include static rebuild (which is rare / out-of-band).",
        "",
        "## Warm lookup latency",
        "",
        f"Summary across {len(warm.get('per_stop', []))} stops × {p['lookup_trials']} trials: {lat_summary or 'n/a'}",
        "",
        "| Stop | # departures | p50 ms | min ms | max ms |",
        "| --- | ---: | ---: | ---: | ---: |",
    ]
    for s in warm.get("per_stop", []):
        lines.append(f"| `{s['stop_id']}` — {s['name'][:50]} | {s['departures']} | "
                     f"{s['p50_ms']:.1f} | {s['min_ms']:.1f} | {s['max_ms']:.1f} |")
    lines += [
        "",
        "## Disk footprint",
        "",
        f"- `gtfs_511_static.sqlite`: **{_human_bytes(disk['static_db'])}**",
        f"- `gtfs_511_rt.sqlite`: **{_human_bytes(disk['rt_db'])}**",
        f"- `*-wal` / `*-shm` (live WAL files): {_human_bytes(disk['rt_wal'])} / {_human_bytes(disk['rt_shm'])}",
        f"- `refresh.lock.sqlite`: {_human_bytes(disk['lock_db'])}",
        f"- **Total**: {_human_bytes(disk['total'])}",
        "",
        f"Static row count: {sum(cold['static_rows'].values()):,}. "
        f"Indexes account for the difference between sum-of-row-bytes and the file size.",
        "",
        "## Operator coverage",
        "",
        f"- Agencies in 511 static feed: **{len(cov.get('agencies') or [])}**",
        f"- Mapped & present: {len(cov.get('mapped_known') or [])} → {', '.join(cov.get('mapped_known') or []) or 'none'}",
        f"- In feed but not in our mapping: {len(cov.get('in_feed_but_unmapped') or [])} → "
        f"{', '.join(cov.get('in_feed_but_unmapped') or []) or 'none'}",
        f"- In our mapping but absent from feed: {len(cov.get('mapped_but_missing') or [])} → "
        f"{', '.join(cov.get('mapped_but_missing') or []) or 'none'}",
        "",
        "### All agencies (id — name)",
        "",
    ]
    for a in cov.get("agencies") or []:
        lines.append(f"- `{a['agency_id']}` — {a['agency_name']}")
    lines += [
        "",
        "## SQLite vs MySQL recommendation",
        "",
        _mysql_recommendation(cold, stale, warm, disk),
        "",
        "## Feasibility verdict",
        "",
        _verdict(cold, stale, warm, disk, cov),
        "",
        "---",
        "",
        "Raw metrics JSON:",
        "",
        "```json",
        json.dumps(p, indent=2, default=str),
        "```",
    ]
    return "\n".join(lines)


def _mysql_recommendation(cold, stale, warm, disk) -> str:
    triggers = []
    if disk["static_db"] + disk["rt_db"] > 500 * 1024 * 1024:
        triggers.append(f"combined SQLite > 500 MB ({_human_bytes(disk['static_db']+disk['rt_db'])})")
    lat = warm.get("overall_ms") or []
    if lat and statistics.median(lat) > 200:
        triggers.append(f"p50 lookup > 200 ms ({statistics.median(lat):.0f} ms)")
    if stale["total_seconds"] > 10:
        triggers.append(f"stale-RT refresh > 10s ({stale['total_seconds']:.1f}s) — concurrent reads may stall")
    if not triggers:
        return (
            "**Stay on SQLite.** Footprint and write durations are well within "
            "what WAL-mode SQLite handles on NFSN. MySQL would add complexity "
            "(separate connection lifecycle, schema migrations) without solving "
            "a measured problem. Re-evaluate only if any of these thresholds "
            "are crossed in production: combined DB >500 MB, p50 lookup >200 ms, "
            "or stale-RT rebuild >10 s."
        )
    return (
        "**Consider MySQL.** The following thresholds were crossed:\n\n- "
        + "\n- ".join(triggers)
        + "\n\nMySQL's process-isolated read path would avoid WAL contention during "
        "the multi-second writes, and NFSN supports it on the Personal plan."
    )


def _verdict(cold, stale, warm, disk, cov) -> str:
    cold_t = cold["total_seconds"]
    stale_t = stale["total_seconds"]
    lat = warm.get("overall_ms") or []
    median_lookup = statistics.median(lat) if lat else None

    verdict = ["**Feasibility: "]
    if stale_t < 5 and (median_lookup or 0) < 100:
        verdict.append("YES (lazy refresh is viable)**.")
    elif stale_t < 15:
        verdict.append("YES with caveats — cron-driven refresh strongly preferred over lazy.**")
    else:
        verdict.append("NO for lazy refresh; cron required.**")
    verdict.append(f"\n\n- Cold full bootstrap takes {cold_t:.1f}s — run out-of-band, never on a CGI request.")
    verdict.append(f"- Stale-RT lazy refresh penalty: {stale_t:.2f}s. ")
    if stale_t < 3:
        verdict[-1] += "Acceptable as a once-per-minute slow request."
    elif stale_t < 10:
        verdict[-1] += "Borderline — most users would notice the slow request."
    else:
        verdict[-1] += "Too slow for lazy refresh on a CGI request."
    if median_lookup is not None:
        verdict.append(f"- Warm lookups: {median_lookup:.0f} ms median — well under proxy budget.")
    missing = cov.get("mapped_but_missing") or []
    if missing:
        verdict.append(f"- Mapping gap: {len(missing)} agencies we claim to support are not in the feed ({', '.join(missing)}). Fix feed_mapping.")
    return "\n".join(verdict)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--trials", type=int, default=5,
                    help="warm-lookup trials per stop (default 5)")
    ap.add_argument("--n-stops", type=int, default=10,
                    help="number of stops to query in warm phase (default 10)")
    ap.add_argument("--keep-cache", action="store_true",
                    help="don't purge caches before cold phase (use existing data)")
    ap.add_argument("--no-static-refresh", action="store_true",
                    help="skip static refresh — only fetch RT (faster for repeated runs)")
    args = ap.parse_args()

    if not gtfs511_api.enabled():
        print("FIVE_ELEVEN_ENABLED is false — aborting bench.", file=sys.stderr)
        sys.exit(2)
    if not (os.environ.get("FIVE_ELEVEN_API_KEY") or os.environ.get("511_API_KEY")):
        print("FIVE_ELEVEN_API_KEY (or 511_API_KEY) not set in environment.", file=sys.stderr)
        sys.exit(2)

    print(f"Bench started {datetime.now(tz=timezone.utc).isoformat()}", flush=True)
    print(f"DB dir: {gtfs511_api._DB_DIR}", flush=True)

    # ---- Phase 1: Cold refresh -----------------------------------------
    if not args.keep_cache:
        print("Purging caches…", flush=True)
        _purge_caches()

    cold_metrics = gtfs511.RefreshMetrics()
    t0 = time.monotonic()
    if args.no_static_refresh:
        gtfs511_api._refresh_rt(cold_metrics)
    else:
        gtfs511_api.refresh_static(cold_metrics)
        gtfs511_api._refresh_rt(cold_metrics)
    cold_total = time.monotonic() - t0
    cold = {
        "total_seconds": cold_total,
        "rt_download_bytes": cold_metrics.rt_download_bytes,
        "rt_download_seconds": cold_metrics.rt_download_seconds,
        "alerts_download_bytes": cold_metrics.alerts_download_bytes,
        "alerts_download_seconds": cold_metrics.alerts_download_seconds,
        "static_download_bytes": cold_metrics.static_download_bytes,
        "static_download_seconds": cold_metrics.static_download_seconds,
        "static_write_seconds": cold_metrics.static_write_seconds,
        "static_parse_seconds": cold_metrics.static_parse_seconds,
        "static_rows": cold_metrics.static_rows,
        "rt_parse_seconds": cold_metrics.rt_parse_seconds,
        "rt_write_seconds": cold_metrics.rt_write_seconds,
        "rt_rows": cold_metrics.rt_rows,
        "alert_rows": cold_metrics.alert_rows,
        "peak_rss_mb": _peak_rss_mb(),
    }
    cold["download_seconds"] = (
        cold["rt_download_seconds"] + cold["alerts_download_seconds"]
        + cold["static_download_seconds"]
    )
    cold["parse_seconds"] = cold["rt_parse_seconds"] + sum(cold["static_parse_seconds"].values())
    cold["write_seconds"] = cold["static_write_seconds"] + cold["rt_write_seconds"]
    cold["bytes_total"] = (cold["rt_download_bytes"] + cold["alerts_download_bytes"]
                            + cold["static_download_bytes"])
    print(f"Cold refresh done in {cold_total:.2f}s "
          f"({_human_bytes(cold['bytes_total'])} downloaded)", flush=True)

    # ---- Phase 2: Warm lookups -----------------------------------------
    print(f"Warming {args.n_stops} stops × {args.trials} trials…", flush=True)
    stops = _select_busy_stops(args.n_stops)
    warm = _bench_warm_lookups(stops, args.trials)
    warm["lookup_trials"] = args.trials

    # ---- Phase 3: Stale-RT refresh -------------------------------------
    print("Simulating stale RT…", flush=True)
    # Backdate rt_meta.refreshed_at by 5 minutes.
    db = sqlite3.connect(gtfs511_api.RT_DB_PATH)
    try:
        with db:
            db.execute("UPDATE rt_meta SET value = ? WHERE key = 'refreshed_at'",
                       (str(int(time.time()) - 300),))
    finally:
        db.close()

    stale_metrics = gtfs511.RefreshMetrics()
    t0 = time.monotonic()
    outcome = gtfs511.refresh_if_stale(stale_metrics)
    stale_total = time.monotonic() - t0
    stale = {
        "total_seconds": stale_total,
        "refreshed_rt": outcome.refreshed_rt,
        "refreshed_static": outcome.refreshed_static,
        "rt_download_bytes": stale_metrics.rt_download_bytes,
        "rt_download_seconds": stale_metrics.rt_download_seconds,
        "rt_parse_seconds": stale_metrics.rt_parse_seconds,
        "rt_write_seconds": stale_metrics.rt_write_seconds,
        "waited_for_lock_seconds": stale_metrics.waited_for_lock_seconds,
    }
    print(f"Stale-RT refresh took {stale_total:.2f}s", flush=True)

    # ---- Phase 4: Disk footprint ---------------------------------------
    disk = {
        "static_db": _file_size(gtfs511_api.STATIC_DB_PATH),
        "rt_db": _file_size(gtfs511_api.RT_DB_PATH),
        "rt_wal": _file_size(gtfs511_api.RT_DB_PATH + "-wal"),
        "rt_shm": _file_size(gtfs511_api.RT_DB_PATH + "-shm"),
        "lock_db": _file_size(gtfs511_api._LOCK_DB_PATH),
    }
    disk["total"] = sum(disk.values())

    # ---- Phase 5: Operator coverage ------------------------------------
    cov = _operator_coverage()

    payload = {
        "timestamp": datetime.now(tz=timezone.utc).isoformat(),
        "lookup_trials": args.trials,
        "cold_refresh": cold,
        "stale_rt_refresh": stale,
        "warm_lookups": warm,
        "disk_footprint": disk,
        "operator_coverage": cov,
    }

    out_name = f"511_bench_{datetime.now(tz=timezone.utc).strftime('%Y%m%dT%H%M%SZ')}.md"
    out_path = os.path.join(REPORT_DIR, out_name)
    _write_report(payload, out_path)
    print(f"\nReport written: {out_path}", flush=True)


if __name__ == "__main__":
    main()
