import os
os.environ.setdefault("TRANSITLAND_API_KEY", "test-key")
os.environ.setdefault("APP_API_KEYS", "test-key")
os.environ.setdefault("STATS_PATH_SECRET", "test-stats-secret")

from datetime import datetime, timezone, timedelta
from stats import _parse_entries, _classify, compute_stats, LogEntry

_TS_FMT = "%d/%b/%Y:%H:%M:%S %z"


def _entry(ip="1.2.3.4", ts_offset_hours=0, path="/api/v2/rest/stops",
           query="", status=200, now=None):
    if now is None:
        now = datetime.now(tz=timezone.utc)
    ts = now - timedelta(hours=ts_offset_hours)
    return LogEntry(ip=ip, ts=ts, method="GET", path=path, query=query, status=status)


def _log_line(ip="1.2.3.4", ts="10/May/2026:12:00:00 +0000",
              uri="/api/v2/rest/stops", status=200):
    return f'{ip} - - [{ts}] "GET {uri} HTTP/1.1" {status} 512 "-" "test" 100'


# ── Parsing ───────────────────────────────────────────────────────────────────

def test_parse_entries_basic():
    lines = _log_line()
    entries = list(_parse_entries(lines))
    assert len(entries) == 1
    e = entries[0]
    assert e.ip == "1.2.3.4"
    assert e.method == "GET"
    assert e.path == "/api/v2/rest/stops"
    assert e.query == ""
    assert e.status == 200


def test_parse_entries_query_string():
    lines = _log_line(uri="/api/v2/rest/departures?onestop_ids=a,b,c")
    entries = list(_parse_entries(lines))
    assert entries[0].path == "/api/v2/rest/departures"
    assert entries[0].query == "onestop_ids=a,b,c"


def test_parse_entries_skips_malformed():
    entries = list(_parse_entries("this is not a log line\n" + _log_line()))
    assert len(entries) == 1


def test_parse_entries_multiple_lines():
    data = "\n".join([
        _log_line(ip="1.1.1.1"),
        _log_line(ip="2.2.2.2"),
        _log_line(ip="3.3.3.3"),
    ])
    entries = list(_parse_entries(data))
    assert len(entries) == 3
    assert [e.ip for e in entries] == ["1.1.1.1", "2.2.2.2", "3.3.3.3"]


# ── Classification ─────────────────────────────────────────────────────────────

def test_classify_stops():
    e = _entry(path="/api/v2/rest/stops")
    assert _classify(e) == ("stops", 1, 0)


def test_classify_stops_error():
    e = _entry(path="/api/v2/rest/stops", status=502)
    assert _classify(e) == ("stops", 0, 0)


def test_classify_departures():
    e = _entry(path="/api/v2/rest/stops/99/departures")
    assert _classify(e) == ("departures", 1, 0)


def test_classify_departures_error():
    e = _entry(path="/api/v2/rest/stops/99/departures", status=504)
    assert _classify(e) == ("departures", 0, 0)


def test_classify_batch_one_id():
    e = _entry(path="/api/v2/rest/departures", query="onestop_ids=abc")
    assert _classify(e) == ("departures_batch", 2, 0)


def test_classify_batch_three_ids():
    e = _entry(path="/api/v2/rest/departures", query="onestop_ids=a,b,c")
    assert _classify(e) == ("departures_batch", 6, 0)


def test_classify_batch_six_ids():
    e = _entry(path="/api/v2/rest/departures", query="onestop_ids=a,b,c,d,e,f")
    assert _classify(e) == ("departures_batch", 12, 0)


def test_classify_batch_error():
    e = _entry(path="/api/v2/rest/departures", query="onestop_ids=a,b,c", status=502)
    assert _classify(e) == ("departures_batch", 0, 0)


def test_classify_geocode():
    e = _entry(path="/api/v2/rest/geocode")
    assert _classify(e) == ("geocode", 0, 1)


def test_classify_geocode_error():
    e = _entry(path="/api/v2/rest/geocode", status=504)
    assert _classify(e) == ("geocode", 0, 0)


def test_classify_healthz():
    e = _entry(path="/healthz")
    assert _classify(e) == ("healthz", 0, 0)


def test_classify_other():
    e = _entry(path="/unknown/path")
    assert _classify(e) == ("other", 0, 0)


# ── Aggregation ───────────────────────────────────────────────────────────────

def test_compute_stats_empty():
    windows, oldest, newest = compute_stats([])
    assert oldest is None
    assert newest is None
    assert windows["all"]["inbound_total"] == 0


def test_compute_stats_oldest_newest():
    now = datetime(2026, 5, 10, 12, 0, 0, tzinfo=timezone.utc)
    entries = [
        _entry(ts_offset_hours=2, now=now),
        _entry(ts_offset_hours=0, now=now),
        _entry(ts_offset_hours=5, now=now),
    ]
    _, oldest, newest = compute_stats(entries, now=now)
    assert oldest == now - timedelta(hours=5)
    assert newest == now


def test_compute_stats_window_counts():
    now = datetime(2026, 5, 10, 12, 0, 0, tzinfo=timezone.utc)
    entries = [
        _entry(ts_offset_hours=0.5, now=now),   # in hour, day, week, all
        _entry(ts_offset_hours=12, now=now),     # in day, week, all
        _entry(ts_offset_hours=72, now=now),     # in week, all
        _entry(ts_offset_hours=200, now=now),    # in all only
    ]
    windows, _, _ = compute_stats(entries, now=now)
    assert windows["hour"]["inbound_total"] == 1
    assert windows["day"]["inbound_total"] == 2
    assert windows["week"]["inbound_total"] == 3
    assert windows["all"]["inbound_total"] == 4


def test_compute_stats_unique_ips():
    now = datetime(2026, 5, 10, 12, 0, 0, tzinfo=timezone.utc)
    entries = [
        _entry(ip="1.1.1.1", ts_offset_hours=0, now=now),
        _entry(ip="1.1.1.1", ts_offset_hours=0, now=now),
        _entry(ip="2.2.2.2", ts_offset_hours=0, now=now),
    ]
    windows, _, _ = compute_stats(entries, now=now)
    assert windows["all"]["unique_ips"] == 2
    assert windows["hour"]["unique_ips"] == 2


def test_compute_stats_transitland_calls():
    now = datetime(2026, 5, 10, 12, 0, 0, tzinfo=timezone.utc)
    entries = [
        _entry(path="/api/v2/rest/stops", now=now),             # 1 TL
        _entry(path="/api/v2/rest/stops/1/departures", now=now),# 1 TL
        _entry(path="/api/v2/rest/departures",
               query="onestop_ids=a,b,c", now=now),             # 6 TL
        _entry(path="/api/v2/rest/geocode", now=now),           # 0 TL, 1 nom
    ]
    windows, _, _ = compute_stats(entries, now=now)
    assert windows["all"]["transitland_calls"] == 8
    assert windows["all"]["nominatim_calls"] == 1


def test_compute_stats_errors_count_inbound_not_upstream():
    now = datetime(2026, 5, 10, 12, 0, 0, tzinfo=timezone.utc)
    entries = [
        _entry(path="/api/v2/rest/stops", status=502, now=now),
        _entry(path="/api/v2/rest/departures",
               query="onestop_ids=a,b", status=502, now=now),
    ]
    windows, _, _ = compute_stats(entries, now=now)
    assert windows["all"]["inbound_total"] == 2
    assert windows["all"]["error_count"] == 2
    assert windows["all"]["transitland_calls"] == 0


def test_compute_stats_by_endpoint():
    now = datetime(2026, 5, 10, 12, 0, 0, tzinfo=timezone.utc)
    entries = [
        _entry(path="/api/v2/rest/stops", now=now),
        _entry(path="/api/v2/rest/stops", now=now),
        _entry(path="/api/v2/rest/geocode", now=now),
    ]
    windows, _, _ = compute_stats(entries, now=now)
    assert windows["all"]["by_endpoint"]["stops"] == 2
    assert windows["all"]["by_endpoint"]["geocode"] == 1


def test_compute_stats_top_ips_sorted():
    now = datetime(2026, 5, 10, 12, 0, 0, tzinfo=timezone.utc)
    entries = (
        [_entry(ip="heavy", now=now)] * 10 +
        [_entry(ip="light", now=now)] * 2
    )
    windows, _, _ = compute_stats(entries, now=now)
    top = windows["all"]["top_ips"]
    assert top[0]["ip"] == "heavy"
    assert top[0]["total"] == 10
    assert top[1]["ip"] == "light"
    assert top[1]["total"] == 2


def test_compute_stats_top_ips_capped_at_25():
    now = datetime(2026, 5, 10, 12, 0, 0, tzinfo=timezone.utc)
    entries = [_entry(ip=f"ip{i}", now=now) for i in range(30)]
    windows, _, _ = compute_stats(entries, now=now)
    assert len(windows["all"]["top_ips"]) == 25
