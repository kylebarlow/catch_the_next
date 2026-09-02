# Server performance options (511 realtime path)

Written 2026-09-02 from measurements on the production NFSN host and locally.
Scope: everything that can make an *uncached* departures request fast
**without** yet moving the 511 refresh out of the request path. The last
section covers what only decoupling can buy.

## 1. Where the ~10 s goes today

Per uncached `GET /api/v2/rest/departures?onestop_ids=<one Caltrain stop>`
(realtime snapshot older than 60 s). All numbers measured on the NFSN host
unless noted.

| Step | Time | Notes |
|---|---|---|
| Apache CGI spawn + Python startup + imports | 0.65 s | imports alone 0.26 s: bottle 0.08, `gtfs_realtime_pb2` 0.09 (pure-Python protobuf class building) |
| Download RG tripupdates (2.46 MB) then alerts (82 KB), sequential | 2.0 s | 511 ignores `Accept-Encoding: gzip`; no ETag/Last-Modified; `If-Modified-Since` still returns 200 |
| Parse tripupdates protobuf | 2.75 s | `google.protobuf` is the **pure-Python** implementation on the host (`api_implementation.Type() == "python"`) |
| Build `gtfs_511_rt.sqlite` (84k rows) | 3.9 s | indexes are created *before* the inserts, so every insert updates two B-trees |
| Departure lookup | 0.47 s cold / 0.02 s warm | 205 MB static DB, cold page cache |
| Lock wait when another request is mid-build | up to 6 s | observed once during measurement |

Host facts that matter: FreeBSD 14, Python 3.11.14, SQLite 3.50.4,
`cc`/`clang`/`gcc14`, **`rustc 1.89` + `cargo`**, **`go 1.24`**, Python
headers present. Sequential disk write is ~230 MB/s (not the bottleneck).
The 511 key is rate-limited to **60 requests per hour**
(`RateLimit-Limit: 60` header on every response; raisable on request to
511).

Feed sizes (measured, one snapshot):

| `agency=` | tripupdates bytes | download |
|---|---|---|
| RG (regional, what we fetch now) | 2,461,810 | 0.6–1.5 s |
| SF (Muni) | 1,031,879 | 2.1 s |
| AC | 328,012 | 0.6 s |
| SC (VTA) | 310,284 | 0.6 s |
| BA (BART) | 40,175 | 0.14 s |
| CT (Caltrain) | 6,699 | 0.11 s |

## 2. Tier 1 — keep Python + CGI + SQLite, fix the measured hot spots

All four are verified on the host; together they take the uncached path from
~10 s to roughly **2.5–3 s** with no architectural change.

### 2.1 Native protobuf extension (2.75 s → ~0.15 s)

`pip install --no-binary :all: protobuf==7.35.1` compiles the bundled upb
extension on the host in **31 s** and yields `impl=upb`. Measured parse of
the 2.46 MB feed: 0.013 s parse + 0.10 s to walk the entities in Python
(vs 2.71 s pure Python). Change `deploy/push.sh` to install with
`--no-binary protobuf` into `/home/protected/pylib`, and add a `/healthz`
check that reports the implementation type so a regression is visible.

### 2.2 Create indexes after the bulk insert (3.9 s → 0.5 s)

Measured on the host with the same feed bytes:

| Variant | write |
|---|---|
| as-is (indexes in DDL before inserts) | 3.88 s |
| indexes created after `executemany` | 0.49 s |
| + `PRAGMA cache_size=-65536`, `page_size=16384` | 0.37 s |

One-line reorder in `rt_db.build_rt_db`. Journal/synchronous are already off.

### 2.3 Download tripupdates and alerts concurrently (2.0 s → ~1.5 s)

Two threads in `api._download_rt_data`. Also give alerts their own, longer
TTL (5 min): they change rarely, and it halves the 511 calls per refresh
cycle, which matters under the 60/hour cap.

### 2.4 Lazy-import protobuf; trim startup (~0.1 s per request)

Import `google.transit.gtfs_realtime_pb2` inside the refresh path only; most
requests (cache hits, fresh snapshot) never need it. Bottle's 0.08 s is the
floor unless the framework changes. `python -X importtime` on the host is the
tool to re-check after changes.

### 2.5 Smaller items

- The stale-static check will trigger an in-request static rebuild the moment
  the static DB passes 7 days (it is 6.2 days old today) unless the scheduled
  task beats it. Static rebuild took 45 s in May; the refresh lock times out
  at 60 s. Make the in-request path *never* rebuild static: serve with the
  old static DB and log, leaving rebuilds to the scheduled task.
- Deploy the repo: the host is running June-11 code (`routing.py`,
  `validation.py`, `gtfs_util.py` and the newer `proxy.py`/`lookup.py` are
  not deployed).
- Rate limiter log scan is 10 ms; not worth touching.

## 3. Tier 2 — change *what* is fetched and stored

### 3.1 Per-agency feeds instead of the regional bundle

The regional feed is 370× larger than Caltrain's. Fetching
`tripupdates?agency=CT` for a Caltrain stop is 6.7 KB in 0.11 s and parses
in 0.03 s *even with pure-Python protobuf*. This is the single biggest
lever for the common case and it also shrinks the SQLite build to
milliseconds.

Design:
- At static build time, add a `stop_agencies(stop_id, agency_id)` table
  (distinct `routes.agency_id` over trips serving the stop, parent stations
  aggregated). Map GTFS `agency_id` → 511 operator code (the RG static feed
  uses the 511 codes as `agency_id`; verify for each operator and keep an
  override table in `feed_mapping.py`).
- One RT snapshot per agency (`gtfs_511_rt_<AG>.sqlite` or see 3.2) with its
  own `refreshed_at`; refresh only the agencies needed by the stops in the
  request.
- Alerts: `servicealerts?agency=<AG>` likewise, 5 min TTL.

Rate-limit budget (60/hour): calls per minute = number of *distinct agencies
with stale snapshots* requested in that minute. For one user that is 1–2;
for a public app with users across Muni, BART, AC, VTA, Caltrain it can reach
5–6/min = 300+/hour. So this must ship with rate-limit accounting:
- Persist `RateLimit-Remaining` and the window start in `cache.sqlite` on
  every 511 response.
- When remaining is below a reserve (e.g. 10), serve the stale snapshot
  (`FIVE_ELEVEN_RT_MAX_STALE`, then schedule-only) instead of fetching.
- Ask 511 for a higher limit before publicising the app; they grant these on
  request.
- Hybrid fallback: agencies not in a small "hot" set use the RG bundle with
  a longer TTL (e.g. 3 min), so the long tail costs at most one call per
  3 min.

### 3.2 Stop building a relational RT database at all

With native protobuf the parse is 0.15 s; the SQLite RT build then exists
only to share the parsed result across CGI processes. Cheaper shared forms,
measured on the host for the full RG feed:

| Shared form | write | read | size |
|---|---|---|---|
| SQLite (indexes after) | 0.49 s | 0.02 s query | 10.6 MB |
| `marshal` dict `stop_id → [rows]` | 0.065 s | 0.042 s | 2.9 MB |
| raw `.pb` on disk, re-parse per request | 0 | 0.15 s | 2.5 MB |

The marshal form is the fastest end-to-end and removes the lock-around-build
problem (write to temp file, `os.replace`). Alerts can live in the same blob
keyed by `stop_id`/`route_id`. Per-agency blobs (3.1) are then tens of KB.
Keep SQLite only for the static GTFS.

### 3.3 Fetch less often

- Tripupdates TTL 60 s matches 511's publish cadence; keep it.
- Alerts TTL 5 min.
- Static: weekly, out-of-band only (see 2.5).

## 4. Tier 3 — runtime and language

### 4.1 Persistent process on NFSN instead of CGI

NFSN supports daemons on any site type (run script + "Add a Daemon";
Production site type is 5¢/day plus resource usage). A long-lived Python
process (waitress/gunicorn behind the NFSN proxy) removes the 0.65 s
startup, keeps the parsed snapshot in memory (no shared-storage format at
all), and makes a background refresh thread trivial. It also removes the
"fresh interpreter per request" caveats throughout `proxy.py`
(`_response_cache`, nominatim throttle). This is the natural home for
stale-while-revalidate later; even without it, uncached ≈ download + 0.15 s.

Cost: a daemon must be supervised (NFSN restarts it via the run script), and
memory is billed. The code change is small (bottle already serves WSGI).

### 4.2 Rust (or Go) — where it actually pays

Both toolchains are on the host, so no cross-compiling. Three scopes, in
increasing ambition:

1. **Helper binary only** (`gtfsrt-extract`): reads a `.pb`, writes the
   marshal/JSON per-stop blob. Rust + `prost` parses 2.5 MB in ~10 ms. This
   replaces the protobuf Python dependency entirely and is ~200 lines. Payoff
   after 2.1 is small (0.15 s → 0.02 s); do it only if 2.1 proves fragile on
   the host (e.g. a future Python upgrade).
2. **Rust CGI for the hot endpoint** (`/api/v2/rest/departures`), keeping
   Python for stops/geocode/stats/static build. Compiled CGI starts in
   ~5 ms vs 650 ms, `rusqlite` reads the static DB, `ureq`/`reqwest` fetches
   511, `prost` parses. Uncached request ≈ download-bound (0.1 s per-agency,
   1.5 s RG); cached ≈ 20 ms. Apache dispatch by path in `.htaccess`. This is
   the option that beats the Python CGI floor *without* a daemon. Cost:
   duplicating auth, rate limiting, response shaping (`_shape_departures`),
   attribution metadata, and the lookup semantics (parent-station expansion,
   dedup of RT vs schedule, service-date handling) in a second language, and
   keeping them in sync. The existing Python tests can be reused as
   golden-output tests against the Rust binary.
3. **Full rewrite** (all endpoints + static GTFS import). Static build would
   drop from ~45 s to a few seconds and the codebase becomes one language,
   but it is weeks of work for a personal service, and the user-visible
   latency gain over option 2 is nil.

Go is equivalent for options 1–2 (single static binary, fast start,
`modernc.org/sqlite` or cgo); pick by preference. Neither is needed to get
under ~1.5 s; both are needed to get under ~0.5 s on CGI.

### 4.3 Move hosting

A small VPS or Fly.io machine running a persistent process removes the CGI
constraint and the FreeBSD wheel problem at once, at the cost of leaving
NFSN's pricing. Same code change as 4.1.

## 5. Lookup / static-side improvements (independent of the above)

- **Precomputed departures table.** `_sched_pass` joins `stop_times → trips →
  routes` with a `service_id IN (thousands)` list, three times (yesterday,
  today, tomorrow). Build `stop_departures(stop_id, service_id, dep_secs,
  trip_id, route_short_name, trip_headsign, agency_id)` at static-build time
  with an index on `(stop_id, dep_secs)`; the lookup becomes one range scan
  and the `active service_id` set is applied in Python on a few hundred rows.
- **Shrink the static DB** (205 MB): drop columns never read, `VACUUM`, and
  set `PRAGMA mmap_size` on open. Mostly helps the cold-cache 0.47 s.
- `_fetch_agencies` runs a full table scan per pass; cache it per process
  (cheap, it is tiny).

## 6. What only decoupling the fetch can buy

After Tiers 1–2 the uncached request is bounded below by
**startup (0.65 s) + 511 download latency (0.1–1.5 s)**. Nothing in the
parse/store path can remove those. The options that do:

- **Stale-while-revalidate inside CGI**: if the snapshot is ≤ `RT_MAX_STALE`
  (180 s), respond from it immediately and refresh *after* the response by
  forking a detached child (`os.fork` + `setsid`, close stdout/stderr so
  Apache ends the request). No cron, no daemon; the user never waits on 511.
- A daemon (4.1) with a refresh thread, or an NFSN scheduled task. Note the
  60/hour cap: a task that refreshes RG tripupdates *and* alerts every minute
  is 120/hour and will be throttled; per-minute tripupdates alone is exactly
  60/hour with zero headroom. Decoupled refresh therefore needs either a
  raised limit or an on-demand trigger (refresh only agencies requested in
  the last N minutes).

## 7. Recommended order

1. Tier 1 (2.1–2.4) + alerts TTL + never rebuild static in-request + deploy
   the pending repo changes. Expected: 10 s → ~2.5–3 s. Half a day.
2. Per-agency feeds with rate-limit accounting (3.1). Expected: ~1.2 s for
   Caltrain/BART, ~2 s for Muni. One to two days including the
   stop→agency table and tests.
3. Replace the RT SQLite with per-agency marshal blobs (3.2). Simplifies
   locking; ~0.3 s saved. Half a day.
4. Precomputed `stop_departures` (5). Cold lookups 0.47 s → ~0.05 s.
5. Decide on the last 0.65 s: stale-while-revalidate in CGI (cheapest),
   daemon (cleanest), or Rust CGI for `/departures` (fastest without a
   daemon). Only at this point does a language change have measurable
   user-facing value.
