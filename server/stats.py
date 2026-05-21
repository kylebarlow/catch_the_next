import os
import re
import sys
import time
from collections import defaultdict
from dataclasses import dataclass
from datetime import datetime, timezone, timedelta
from urllib.parse import parse_qs
import counters
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
    r'(?: "[^"]*" "(?P<ua>[^"]*)")?'
)

_STOPS_PATH       = re.compile(r'^/api/v2/rest/stops$')
_DEPARTURES_PATH  = re.compile(r'^/api/v2/rest/stops/\d+/departures$')
_BATCH_PATH       = re.compile(r'^/api/v2/rest/departures$')
_GEOCODE_PATH     = re.compile(r'^/api/v2/rest/geocode$')
_HEALTHZ_PATH     = re.compile(r'^/healthz$')
_INTERNAL_PATH    = re.compile(r'^/_internal/')

# Mirror of proxy.py's _RESPONSE_CACHE_TTL — used to count how many requests
# would have been absorbed by a working cross-request cache.
_DUPLICATE_WINDOW_SECONDS = 50

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
    user_agent: str = ""


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
            user_agent=m.group("ua") or "",
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

    if _INTERNAL_PATH.match(p):
        return "internal", 0, 0

    return "other", 0, 0


def _client_kind(entry: LogEntry):
    """Coarse classification of the client based on user agent."""
    ua = entry.user_agent or ""
    if "CatchTheNext" in ua:
        return "app"
    if not ua or ua == "-":
        return "unknown"
    if "Mozilla" in ua or "AppleWebKit" in ua:
        return "browser"
    return "other"


def _dedupe_key(entry: LogEntry):
    """Identifier for "the same request" — used to detect requests that
    would have hit the proxy's response cache. Returns None for endpoints
    that aren't cached (geocode, internal, healthz, other)."""
    p = entry.path
    if _STOPS_PATH.match(p):
        q = parse_qs(entry.query)
        return ("stops", q.get("lat", [""])[0], q.get("lon", [""])[0], q.get("radius", [""])[0])
    if _BATCH_PATH.match(p):
        q = parse_qs(entry.query)
        ids = q.get("onestop_ids", [""])[0]
        ids = ",".join(sorted(s for s in ids.split(",") if s))
        return ("departures", ids, q.get("next", [""])[0])
    if _DEPARTURES_PATH.match(p):
        return ("departures_single", p, entry.query)
    return None


def compute_stats(entries, now=None):
    """Return (result_dict, oldest_ts, newest_ts).

    The result has top-level keys:
      - windows: per-window aggregates (hour / day / week / all)
      - daily:   per-day rollups for the last 14 days (oldest first)
      - clients: counts per client kind (app/browser/unknown/other) for week
    """
    if now is None:
        now = datetime.now(tz=timezone.utc)

    acc = {
        wname: {
            "inbound": 0,
            "transitland": 0,
            "nominatim": 0,
            "errors": 0,
            "by_ep": {},
            "ip_ep": {},
            "by_client": {},
            "app_inbound": 0,
            "app_transitland": 0,
            "dup_inbound": 0,
            "dup_transitland_saved": 0,
            "_recent": {},  # dedupe-key → last ts seen
        }
        for wname, _ in _WINDOWS
    }
    cutoffs = {wname: (now - delta) if delta else None for wname, delta in _WINDOWS}

    # 14-day daily rollup: keyed by ISO date (YYYY-MM-DD).
    daily = defaultdict(lambda: {"inbound": 0, "transitland": 0, "app": 0})
    daily_cutoff = now - timedelta(days=14)

    oldest = None
    newest = None

    for entry in entries:
        if oldest is None or entry.ts < oldest:
            oldest = entry.ts
        if newest is None or entry.ts > newest:
            newest = entry.ts

        label, tl, nom = _classify(entry)
        kind = _client_kind(entry)
        dkey = _dedupe_key(entry)

        if entry.ts >= daily_cutoff and label not in ("internal", "other", "healthz"):
            d = entry.ts.strftime("%Y-%m-%d")
            daily[d]["inbound"] += 1
            daily[d]["transitland"] += tl
            if kind == "app":
                daily[d]["app"] += 1

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
            wd["by_client"][kind] = wd["by_client"].get(kind, 0) + 1
            if kind == "app":
                wd["app_inbound"] += 1
                wd["app_transitland"] += tl
            ip_ep = wd["ip_ep"]
            if entry.ip not in ip_ep:
                ip_ep[entry.ip] = {}
            ip_ep[entry.ip][label] = ip_ep[entry.ip].get(label, 0) + 1

            if dkey is not None:
                # Same-key request from any client within cache TTL — these
                # are requests a working cross-request response cache would
                # have absorbed without a Transitland call.
                last_ts = wd["_recent"].get(dkey)
                if last_ts is not None and (entry.ts - last_ts).total_seconds() < _DUPLICATE_WINDOW_SECONDS:
                    wd["dup_inbound"] += 1
                    wd["dup_transitland_saved"] += tl
                wd["_recent"][dkey] = entry.ts

    windows = {}
    for wname, _ in _WINDOWS:
        wd = acc[wname]
        top_ips = sorted(
            [{"ip": ip, "total": sum(ep.values()), "by_endpoint": ep}
             for ip, ep in wd["ip_ep"].items()],
            key=lambda x: -x["total"],
        )[:25]
        windows[wname] = {
            "inbound_total":         wd["inbound"],
            "unique_ips":            len(wd["ip_ep"]),
            "transitland_calls":     wd["transitland"],
            "nominatim_calls":       wd["nominatim"],
            "error_count":           wd["errors"],
            "by_endpoint":           wd["by_ep"],
            "by_client":             wd["by_client"],
            "app_inbound":           wd["app_inbound"],
            "app_transitland_calls": wd["app_transitland"],
            "dup_inbound":           wd["dup_inbound"],
            "dup_transitland_saved": wd["dup_transitland_saved"],
            "top_ips":               top_ips,
        }

    # Fill in any missing days in the 14-day window with zeros so the chart
    # is continuous.
    daily_out = []
    for i in range(13, -1, -1):
        d = (now - timedelta(days=i)).strftime("%Y-%m-%d")
        row = daily.get(d, {"inbound": 0, "transitland": 0, "app": 0})
        daily_out.append({"date": d, **row})

    return windows, oldest, newest, daily_out


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
    windows, oldest, newest, daily = compute_stats(_parse_entries(data), now)

    # Enrich windows with actual upstream call counts from the counter DB.
    now_epoch = now.timestamp()
    counter_windows = {
        "hour": now_epoch - 3600,
        "day":  now_epoch - 86400,
        "week": now_epoch - 86400 * 7,
        "all":  None,
    }
    for wname, since in counter_windows.items():
        if since is not None:
            five11   = counters.query_since("five11_calls",      since)
            tl       = counters.query_since("transitland_calls", since)
            nominatim = counters.query_since("nominatim_calls",  since)
        else:
            five11   = counters.query_all("five11_calls")
            tl       = counters.query_all("transitland_calls")
            nominatim = counters.query_all("nominatim_calls")
        windows[wname]["five11_calls_actual"]      = five11
        windows[wname]["transitland_calls_actual"] = tl
        windows[wname]["nominatim_calls_actual"]   = nominatim

    # Enrich daily rows with per-day 511 and Transitland counts.
    daily_cutoff_epoch = (now - timedelta(days=14)).timestamp()
    daily_five11 = counters.query_daily("five11_calls",      daily_cutoff_epoch)
    daily_tl     = counters.query_daily("transitland_calls", daily_cutoff_epoch)
    for row in daily:
        row["five11"]      = daily_five11.get(row["date"], 0)
        row["transitland_actual"] = daily_tl.get(row["date"], 0)

    result = {
        "windows":        windows,
        "daily":          daily,
        "log_path":       path,
        "log_size_bytes": size,
        "oldest_entry":   oldest.isoformat() if oldest else None,
        "newest_entry":   newest.isoformat() if newest else None,
        "generated_at":   now.isoformat(),
    }

    _cache["v"] = (result, time.monotonic(), cache_key)
    return result


# ── HTML renderer ──────────────────────────────────────────────────────────────

_ALL_ENDPOINTS = ["stops", "departures", "departures_batch", "geocode", "healthz", "internal", "other"]
_ALL_CLIENTS = ["app", "browser", "unknown", "other"]

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
td.bar{text-align:left;padding:0 4px}
.bar-fill{display:inline-block;height:10px;background:#3d7a3d;vertical-align:middle}
.bar-fill.tl{background:#3d5f7a}
.bar-fill.five11{background:#7a5f3d}
tr:hover td{background:#1a1a1a}
.sep{margin:32px 0 0;border:none;border-top:1px solid #333}
.note{color:#888;margin:4px 0 12px;font-size:12px}
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
    inbound_rows = [
        ("Inbound requests", "inbound_total"),
        ("  ↳ from app",     "app_inbound"),
        ("Unique IPs",       "unique_ips"),
        ("Errors (5xx)",     "error_count"),
    ]
    for label, key in inbound_rows:
        parts.append(_td_row(label, [w[n][key] for n in wnames]))
    parts.append("</table>")

    parts.append("<h2>Upstream API calls (tracked)</h2>")
    parts.append(
        '<div class="note">Actual calls recorded at call-time. '
        'Counters began accumulating after the 511 pipeline was deployed — '
        'zeros indicate no data yet for that window.</div>'
    )
    parts.append("<table>")
    parts.append(_th_row([""] + wlabels))
    upstream_rows = [
        ("511.org (direct GTFS-RT)", "five11_calls_actual"),
        ("Transitland API",          "transitland_calls_actual"),
        ("Nominatim (geocode)",      "nominatim_calls_actual"),
    ]
    for label, key in upstream_rows:
        parts.append(_td_row(label, [w[n][key] for n in wnames]))
    # Show ratio if there's any data
    ratio_cells = []
    for n in wnames:
        five11 = w[n]["five11_calls_actual"]
        tl = w[n]["transitland_calls_actual"]
        total = five11 + tl
        if total:
            pct = int(100 * five11 / total)
            ratio_cells.append(f"{pct}% 511")
        else:
            ratio_cells.append("—")
    parts.append("<tr><td class='lbl'>  ↳ 511 share of departures</td>" +
                 "".join(f"<td>{r}</td>" for r in ratio_cells) + "</tr>")
    parts.append("</table>")

    # ── Cache-savings (duplicate requests within the proxy's response TTL) ──
    parts.append("<h2>Cacheable duplicates (within 50s window)</h2>")
    parts.append(
        '<div class="note">Inbound requests with identical params seen '
        'within the proxy response-cache TTL. With a working cross-request '
        'cache, these would not have hit Transitland. High numbers mean the '
        'in-process cache is being defeated (e.g. by CGI process churn).</div>'
    )
    parts.append("<table>")
    parts.append(_th_row([""] + wlabels))
    pct_row = []
    for n in wnames:
        inb = w[n]["inbound_total"]
        dup = w[n]["dup_inbound"]
        pct_row.append(f"{(100 * dup / inb):.0f}%" if inb else "—")
    parts.append(_td_row("Duplicate inbound", [w[n]["dup_inbound"] for n in wnames]))
    parts.append(_td_row("Duplicate TL calls saveable", [w[n]["dup_transitland_saved"] for n in wnames]))
    parts.append("<tr><td class='lbl'>% inbound dup</td>" +
                 "".join(f"<td>{p}</td>" for p in pct_row) + "</tr>")
    parts.append("</table>")

    # ── Daily timeline (last 14 days) ─────────────────────────────────────────
    daily = stats.get("daily") or []
    if daily:
        max_inb = max((d["inbound"] for d in daily), default=0) or 1
        max_511 = max((d.get("five11", 0) for d in daily), default=0)
        max_tl  = max((d.get("transitland_actual", 0) for d in daily), default=0)
        max_upstream = max(max_511, max_tl) or 1
        parts.append("<h2>Daily timeline (last 14 days)</h2>")
        parts.append(
            '<div class="note">Inbound/App counts from access log; '
            '511 and Transitland counts from tracked call counters '
            '(zeros before counter deployment). '
            'Excludes <code>/_internal</code>, <code>/healthz</code>.</div>'
        )
        parts.append("<table>")
        parts.append(_th_row(["Date", "Inbound", "", "App", "511", "", "Transitland", ""]))
        for d in daily:
            inb_bar = int(120 * d["inbound"] / max_inb)
            five11_val = d.get("five11", 0)
            tl_val = d.get("transitland_actual", 0)
            five11_bar = int(120 * five11_val / max_upstream)
            tl_bar = int(120 * tl_val / max_upstream)
            parts.append(
                f"<tr><td class='lbl'>{d['date']}</td>"
                f"<td>{_fmt(d['inbound'])}</td>"
                f"<td class='bar'><span class='bar-fill' style='width:{inb_bar}px'></span></td>"
                f"<td>{_fmt(d['app'])}</td>"
                f"<td>{_fmt(five11_val)}</td>"
                f"<td class='bar'><span class='bar-fill five11' style='width:{five11_bar}px'></span></td>"
                f"<td>{_fmt(tl_val)}</td>"
                f"<td class='bar'><span class='bar-fill tl' style='width:{tl_bar}px'></span></td></tr>"
            )
        parts.append("</table>")

    # ── Client breakdown ──────────────────────────────────────────────────────
    parts.append("<h2>Client Breakdown</h2>")
    parts.append("<table>")
    parts.append(_th_row(["Client"] + wlabels))
    all_clients = set()
    for n in wnames:
        all_clients.update(w[n].get("by_client", {}).keys())
    cl_order = [c for c in _ALL_CLIENTS if c in all_clients] + sorted(all_clients - set(_ALL_CLIENTS))
    for c in cl_order:
        parts.append(_td_row(c, [w[n].get("by_client", {}).get(c, 0) for n in wnames]))
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
