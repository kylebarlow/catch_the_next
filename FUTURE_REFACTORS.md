# Future refactor candidates

The June 2026 whole-codebase simplification review produced a 2-track refactor plan
(`refactor_implement.md`). All of its phases have landed except the one below, which is
gated. Completed phases — shared ViewModel wiring (`SharedViewModels.kt`), `CommonGraph`
DI base, the shared `DeparturesPipeline` (incl. tile stale-resolution), `Tuning.kt` +
server `config.py` constants, shared `AlertPresentation` logic, the shared `AppJson` Gson
singleton, and the server `validation.py` / `routing.py` modules — are now in the code and
need no further tracking.

## kotlinx.serialization migration — GATED, defer until the current refactor has soaked

The shared-Gson step already landed (`core/.../json/AppJson.kt`, one instance reused at all
seven serialization sites). What remains is the larger swap to kotlinx.serialization, which
removes ~120 lines of DTO boilerplate and the reflection cost that matters for wear startup.
It is gated because phone/wear version skew means sync payloads must stay byte-compatible in
both directions — a disk-format regression silently wipes favorites.

**Where:** `core/.../api/TransitlandClient.kt` (11 private response DTOs), `model/Stop`,
`FeedAttribution`, `tile` cache models (`CachedStopDepartures`/`CachedDeparture`/`Alert`),
`sync/SyncState`/`FavoriteEntry`, phone `TrackingState`.

**Gate (write and pass before any production swap):** golden-file compat tests in
core/shared-android — check in fixtures of *current production* serialized forms (favorites
`favorites_list` payload, `nearby_departures_v2` tile cache, sync data-layer
`Map<String, FavoriteEntry>` payload, cli `favorites.json`, `tracking_state`), plus a
round-trip matrix (Gson-encode→kotlinx-decode and reverse) for every persisted model.

**Steps:** (1) add `kotlinx-serialization-json` + plugin to `gradle/libs.versions.toml`;
(2) `@Serializable` (+ `@SerialName` matching every existing field name) on the DTOs and
persisted models above; (3) shared `Json { ignoreUnknownKeys = true; explicitNulls = false;
encodeDefaults = true }` — verify each choice against the golden tests, not by assumption
(Gson omits nulls; confirm enum encoding for `DepartureTimeSource`/`AlertSeverity`); (4) swap
one store at a time — TransitlandClient DTOs first (wire-only, no persistence risk), sync
payloads **last**; (5) drop the Gson dependency when zero refs remain (`AppJson` is the last
holdout).

**Verify:** full test suite + on-device upgrade test (install current release, create
favorites + tile cache, upgrade to branch build, confirm favorites/tile/attributions
survive).

## Explicitly rejected (don't redo these)

- **Module-level `stats` import in app.py** — the lazy import inside the stats handlers
  is deliberate: under CGI every request re-imports app.py, and only stats requests
  should pay for stats.py.
- **Connection reuse in `server/cache.py`** — per-operation connections are the
  thread-safe choice given the batch path's `ThreadPoolExecutor`, and CGI gives no
  long-lived process to amortize a pool anyway.
- **Centralizing `APP_API_KEYS` in tests/conftest.py** — test modules set different
  values via order-sensitive `os.environ.setdefault`; consolidation changes which value
  wins depending on collection order.
- **micro-opts** (lru_cache on `_synthetic_integer_id`, departure sort-key precompute,
  merging the 3 calendar-date queries in `gtfs511/lookup.py`) — negligible at real
  batch sizes (≤6 stops).
