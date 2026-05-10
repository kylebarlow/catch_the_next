import os
import re
import sys
import time
from dataclasses import dataclass
from datetime import datetime, timezone, timedelta
from urllib.parse import parse_qs
from config import load_config

_cfg = load_config()
_log_path = _cfg["ACCESS_LOG_PATH"]
_max_log_bytes = _cfg["STATS_MAX_LOG_BYTES"]
_cache_seconds = _cfg["STATS_CACHE_SECONDS"]

_TS_FMT = "%d/%b/%Y:%H:%M:%S %z"
_LOG_RE = re.compile(
    r'^(?P<ip>\S+) \S+ \S+ \[(?P<ts>[^\]]+)\] '
    r'"(?P<method>\S+) (?P<uri>\S+)[^"]*" '
    r'(?P<status>\d{3}) \S+'
)

_STOPS_PATH       = re.compile(r'^/api/v2/rest/stops$')
_DEPARTURES_PATH  = re.compile(r'^/api/v2/rest/stops/\d+/departures$')
_BATCH_PATH       = re.compile(r'^/api/v2/rest/departures$')
_GEOCODE_PATH     = re.compile(r'^/api/v2/rest/geocode$')
_HEALTHZ_PATH     = re.compile(r'^/healthz$')

_WINDOWS = [
    ("hour", timedelta(hours=1)),
    ("day",  timedelta(hours=24)),
    ("week", timedelta(days=7)),
    ("all",  None),
]
_WINDOW_LABELS = {"hour": "Last Hour", "day": "Last 24h", "week": "Last 7d", "all": "All Time"}

_log_missing_warned = False


@dataclass
class LogEntry:
    ip: str
    ts: datetime
    method: str
    path: str
    query: str
    status: int


def _parse_entries(data: str):
    for line in data.splitlines():
        m = _LOG_RE.match(line)
        if not m:
            continue
        try:
            ts = datetime.strptime(m.group("ts"), _TS_FMT)
        except ValueError:
            continue
        uri = m.group("uri")
        path, _, query = uri.partition("?")
        yield LogEntry(
            ip=m.group("ip"),
            ts=ts,
            method=m.group("method"),
            path=path,
            query=query,
            status=int(m.group("status")),
        )


def _classify(entry: LogEntry):
    """Return (endpoint_label, transitland_calls, nominatim_calls)."""
    p = entry.path
    ok = entry.status < 500

    if _STOPS_PATH.match(p):
        return "stops", 1 if ok else 0, 0

    if _DEPARTURES_PATH.match(p):
        return "departures", 1 if ok else 0, 0

    if _BATCH_PATH.match(p):
        if ok:
            ids_str = parse_qs(entry.query).get("onestop_ids", [""])[0]
            n = len([s for s in ids_str.split(",") if s.strip()])
        else:
            n = 0
        return "departures_batch", 2 * n, 0

    if _GEOCODE_PATH.match(p):
        return "geocode", 0, 1 if ok else 0

    if _HEALTHZ_PATH.match(p):
        return "healthz", 0, 0

    return "other", 0, 0


def compute_stats(entries, now=None):
    """Return (windows_dict, oldest_ts, newest_ts)."""
    if now is None:
        now = datetime.now(tz=timezone.utc)

    acc = {
        wname: {"inbound": 0, "transitland": 0, "nominatim": 0, "errors": 0, "by_ep": {}, "ip_ep": {}}
        for wname, _ in _WINDOWS
    }
    cutoffs = {wname: (now - delta) if delta else None for wname, delta in _WINDOWS}

    oldest = None
    newest = None

    for entry in entries:
        if oldest is None or entry.ts < oldest:
            oldest = entry.ts
        if newest is None or entry.ts > newest:
            newest = entry.ts

        label, tl, nom = _classify(entry)

        for wname, _ in _WINDOWS:
            cutoff = cutoffs[wname]
            if cutoff is not None and entry.ts < cutoff:
                continue
            wd = acc[wname]
            wd["inbound"] += 1
            wd["transitland"] += tl
            wd["nominatim"] += nom
            if entry.status >= 500:
                wd["errors"] += 1
            wd["by_ep"][label] = wd["by_ep"].get(label, 0) + 1
            ip_ep = wd["ip_ep"]
            if entry.ip not in ip_ep:
                ip_ep[entry.ip] = {}
            ip_ep[entry.ip][label] = ip_ep[entry.ip].get(label, 0) + 1

    result = {}
    for wname, _ in _WINDOWS:
        wd = acc[wname]
        top_ips = sorted(
            [{"ip": ip, "total": sum(ep.values()), "by_endpoint": ep}
             for ip, ep in wd["ip_ep"].items()],
            key=lambda x: -x["total"],
        )[:25]
        result[wname] = {
            "inbound_total":     wd["inbound"],
            "unique_ips":        len(wd["ip_ep"]),
            "transitland_calls": wd["transitland"],
            "nominatim_calls":   wd["nominatim"],
            "error_count":       wd["errors"],
            "by_endpoint":       wd["by_ep"],
            "top_ips":           top_ips,
        }

    return result, oldest, newest


# single-slot cache: {"v": (result, cached_at_monotonic, cache_key)}
_cache = {}


def load_stats():
    global _log_missing_warned
    path = _log_path

    try:
        st = os.stat(path)
        cache_key = (st.st_mtime, st.st_size)
        size = st.st_size
    except OSError:
        cache_key = None
        size = 0

    slot = _cache.get("v")
    if slot and cache_key is not None:
        result, cached_at, cached_key = slot
        if cached_key == cache_key and time.monotonic() - cached_at < _cache_seconds:
            return result

    data = ""
    try:
        with open(path, "rb") as f:
            if size > _max_log_bytes:
                f.seek(-_max_log_bytes, 2)
                f.readline()  # skip partial line
            data = f.read().decode("utf-8", errors="replace")
    except FileNotFoundError:
        if not _log_missing_warned:
            print(f"WARNING: access log not found at {path}", file=sys.stderr)
            _log_missing_warned = True

    now = datetime.now(tz=timezone.utc)
    windows, oldest, newest = compute_stats(_parse_entries(data), now)

    result = {
        "windows":        windows,
        "log_path":       path,
        "log_size_bytes": size,
        "oldest_entry":   oldest.isoformat() if oldest else None,
        "newest_entry":   newest.isoformat() if newest else None,
        "generated_at":   now.isoformat(),
    }

    _cache["v"] = (result, time.monotonic(), cache_key)
    return result


# ── HTML renderer ──────────────────────────────────────────────────────────────

_ALL_ENDPOINTS = ["stops", "departures", "departures_batch", "geocode", "healthz", "other"]

_CSS = """
body{font-family:monospace;background:#111;color:#ddd;margin:24px;font-size:13px}
h1{color:#fff;margin-bottom:4px}
.meta{color:#777;margin-bottom:20px}
h2{color:#aaa;margin:28px 0 6px;font-size:14px}
table{border-collapse:collapse;margin:6px 0}
th,td{padding:3px 14px;border:1px solid #333;white-space:nowrap}
th{background:#222;color:#bbb;text-align:center}
td{text-align:right}
td.lbl{text-align:left;color:#aaa}
td.ip{text-align:left;font-size:12px;color:#ccc}
tr:hover td{background:#1a1a1a}
.sep{margin:32px 0 0;border:none;border-top:1px solid #333}
"""


def _fmt(n):
    return f"{n:,}"


def _th_row(cols):
    return "<tr>" + "".join(f"<th>{c}</th>" for c in cols) + "</tr>"


def _td_row(label, vals):
    cells = f'<td class="lbl">{label}</td>' + "".join(f"<td>{_fmt(v)}</td>" for v in vals)
    return f"<tr>{cells}</tr>"


def render_html(stats: dict) -> str:
    w = stats["windows"]
    wnames = [n for n, _ in _WINDOWS]
    wlabels = [_WINDOW_LABELS[n] for n in wnames]

    gen = stats.get("generated_at", "")
    log_path = stats.get("log_path", "")
    log_size = stats.get("log_size_bytes", 0)
    oldest = stats.get("oldest_entry") or "—"
    newest = stats.get("newest_entry") or "—"

    size_str = f"{log_size / 1024:.1f} KB" if log_size < 1_000_000 else f"{log_size / 1_000_000:.1f} MB"

    parts = []
    parts.append(f"""<!doctype html><html><head>
<meta charset="utf-8">
<meta http-equiv="refresh" content="60">
<title>CTN Proxy Stats</title>
<style>{_CSS}</style>
</head><body>
<h1>CTN Proxy Stats</h1>
<div class="meta">
  Generated: {gen}<br>
  Log: {log_path} ({size_str})<br>
  Range: {oldest[:19] if oldest != '—' else '—'} → {newest[:19] if newest != '—' else '—'}
</div>""")

    # ── Summary ──────────────────────────────────────────────────────────────
    parts.append("<h2>Summary</h2>")
    parts.append("<table>")
    parts.append(_th_row([""] + wlabels))
    rows = [
        ("Inbound requests", "inbound_total"),
        ("Unique IPs",       "unique_ips"),
        ("Transitland calls","transitland_calls"),
        ("Nominatim calls",  "nominatim_calls"),
        ("Errors (5xx)",     "error_count"),
    ]
    for label, key in rows:
        parts.append(_td_row(label, [w[n][key] for n in wnames]))
    parts.append("</table>")

    # ── Endpoint breakdown ────────────────────────────────────────────────────
    parts.append("<h2>Endpoint Breakdown</h2>")
    parts.append("<table>")
    parts.append(_th_row(["Endpoint"] + wlabels))
    all_eps = set()
    for n in wnames:
        all_eps.update(w[n]["by_endpoint"].keys())
    ep_order = [e for e in _ALL_ENDPOINTS if e in all_eps] + sorted(all_eps - set(_ALL_ENDPOINTS))
    for ep in ep_order:
        parts.append(_td_row(ep, [w[n]["by_endpoint"].get(ep, 0) for n in wnames]))
    parts.append("</table>")

    # ── Top IPs (one table per window) ────────────────────────────────────────
    for wname, wlabel in zip(wnames, wlabels):
        top = w[wname]["top_ips"]
        parts.append(f"<hr class='sep'><h2>Top IPs — {wlabel}</h2>")
        if not top:
            parts.append("<p style='color:#555'>No data</p>")
            continue
        ep_cols = sorted({ep for row in top for ep in row["by_endpoint"]},
                         key=lambda e: (_ALL_ENDPOINTS.index(e) if e in _ALL_ENDPOINTS else 99, e))
        parts.append("<table>")
        parts.append(_th_row(["IP", "Total"] + ep_cols))
        for row in top:
            ep_vals = "".join(f"<td>{_fmt(row['by_endpoint'].get(ep, 0))}</td>" for ep in ep_cols)
            parts.append(f'<tr><td class="ip">{row["ip"]}</td>'
                         f'<td>{_fmt(row["total"])}</td>{ep_vals}</tr>')
        parts.append("</table>")

    parts.append("</body></html>")
    return "\n".join(parts)
