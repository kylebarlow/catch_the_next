# Future refactor candidates

Findings from a whole-codebase simplification review (June 2026) that were real but
too large or too behavior-affecting to apply in a cleanup pass. Each entry has enough
context to pick up cold. Small fixes from the same review (shared `server/gtfs_util.py`,
TileState fetch helpers, `DetailsUi.Loaded.feedNames()`, Gson singleton in SyncState,
cli type-mismatch repair) were already applied.

## 1. Deduplicate phone/wear ViewModel factories (~100 lines each)

**Where:** `phone/src/main/kotlin/dev/catchthenext/phone/PhoneViewModelFactory.kt:38-172`
and `wear/src/main/kotlin/dev/catchthenext/wear/MainActivity.kt:149-276`.

**Problem:** The `DeparturesViewModel` construction (the `quickCacheRead` /
`computeState` pipeline wiring around `computeTileState` + `makeFetchNetworkDeparturesBatch`),
plus the `FavoritesViewModel` and `SettingsViewModel` factory blocks, are duplicated
nearly line-for-line. Only real differences: graph object (`PhoneGraph` vs `WearGraph`),
user-agent string, and `peerLabel` ("watch" vs "phone").

**Fix:** Extract a builder in `shared-android` (e.g.
`dev.catchthenext.android.ui.ViewModelWiring`) that takes the graph's collaborators
(client, tile data store, favorites manager, sync engine) plus the few string params,
and returns the configured ViewModels/factories. Phone and wear factories collapse to
parameter lists. Do `SettingsViewModel`/`FavoritesViewModel` first (small, mechanical),
then the departures pipeline.

## 2. Deduplicate the DI graphs themselves

**Where:** `wear/src/main/kotlin/dev/catchthenext/wear/WearGraph.kt:12-40`,
`phone/src/main/kotlin/dev/catchthenext/phone/PhoneGraph.kt:14-60`.

**Problem:** Same double-checked-locking singleton boilerplate for
`transitlandClient()`, `syncStateStore()`, `favoritesManager()` in both modules.

**Fix:** A shared base (object delegating to a `CommonGraph(context, userAgent, ...)`
class in `shared-android`) that owns the lazy singletons; per-app graphs just supply
construction parameters. Pairs naturally with item 1.

## 3. Server: unify 511-vs-Transitland routing in one module

**Where:** `server/proxy.py` — synthetic onestop_id helpers (~lines 51-95) and the
three-way classification "local_always" / "local_or_tl" / "tl" / "none" in the batch
departures path (around `_fetch_one`, ~lines 560-600);
`server/gtfs511/feed_mapping.py:143-162` (`in_bay_area`, `is_bay_area_feed`).

**Problem:** The decision of which upstream serves a stop is split across proxy.py and
feed_mapping.py. Adding a second regional feed or changing the fallback strategy means
edits in both files, and the rules are hard to read as a whole.

**Fix:** `server/routing.py` with
`classify_departure_source(onestop_id, feed_onestop_id) -> Plan` encapsulating all
three rules (synthetic → always local; Bay Area TL stop → local first, TL fallback;
else TL). proxy.py calls it once per stop. Related plan context lives in the memory
note "Transitland usage reduction" and commit `d72d378`.

## 4. Stale-stop resolution doesn't run in the wear tile path (behavior change)

**Where:** `shared-android/.../tile/TileState.kt` `resolveStaleStops()` (~line 169);
callers: `phone/.../PhoneViewModelFactory.kt:102-108`, `wear/.../MainActivity.kt:213-219`.
Missing caller: `wear/.../tile/ClosestStopTileService.kt` (uses
`makeFetchNetworkDeparturesBatch` at ~line 133 but never resolves `isStale`).

**Problem:** When Transitland rotates a stop's integer ID, the proxy flags it stale and
the app UIs re-resolve via nearby-stops + `stop_id` match, but the tile itself never
does — a tile-only user can show stale favorites indefinitely.

**Fix:** Either call `resolveStaleStops()` (and persist the updated favorites) from the
tile service's refresh path, or fold the resolution into `computeTileState` /
`persistDepartures` so every consumer gets it. Decide whether tile-triggered favorite
writes are acceptable given the favorites sync protocol (see
`shared-android/.../sync/`, memory note "Favorites sync architecture").

## 5. Server: validation layer for app.py endpoints

**Where:** `server/app.py` — `_int_param` (~line 15) and the copy-pasted
param-check-then-`HTTPResponse` blocks in stops/geocode (~lines 49-102) and batch
(~lines 122-146) handlers.

**Problem:** Each route re-implements validate-or-return-JSON-error; lat/lon pairing
and float validation aren't covered by the one helper; error shape is enforced only by
convention.

**Fix:** `server/validation.py` with `require_float(name)`, `require_int(name, min, max)`
etc. raising a `ValidationError`, plus a bottle error handler (or small decorator —
remember this is CGI, keep it import-light) that renders the uniform JSON error.

## 6. Centralize tuning constants

**Where (server):** `server/proxy.py` lines ~40-49 (`_RESPONSE_CACHE_TTL=50`,
`_GEOCODE_CACHE_TTL=3600`, `_STOP_ID_CACHE_TTL=180d`, `_BATCH_MAX_STOPS=6`), plus
`_UPSTREAM_STOPS_LIMIT`, `_STOPS_GRID_SLACK_M` near the stops path.
**Where (Kotlin):** `shared-android/.../tile/TileState.kt:17-18`
(`CACHE_TTL_MS=60_000`, `MAX_BATCH_STOPS=4` — note `updateNearbyStopsDepartures` also
has its own `maxStops: Int = 4` default), `wear/.../tile/ClosestStopTileService.kt:48-49`
(refresh threshold), `TileState.kt` `thresholdMeters = 1609` default,
`AddStopViewModel.kt:41,45` (radius 600/1500).

**Problem:** Cache/radius/limit tuning requires grep across files; client and server
each hold half the knobs. Note `MAX_BATCH_STOPS=4` (client) vs `_BATCH_MAX_STOPS=6`
(server) — intentional headroom, but undocumented.

**Fix:** Server: move into `config.py` (already the config home, env-overridable).
Kotlin: a `TileConstants`/`Defaults` object in shared-android; route the AddStop radii
through `DistanceUnitStore`, which already manages the user-facing threshold.

## 7. Remove or wire up the single-stop tile fetcher

**Where:** `shared-android/.../tile/TileState.kt` `makeFetchNetworkDepartures()`
(single-stop variant) and its tests in `TileStateTest.kt` (~lines 273-400).

**Problem:** Production only uses `makeFetchNetworkDeparturesBatch` (call sites:
PhoneViewModelFactory, wear MainActivity, ClosestStopTileService). The single-stop
variant is exercised only by tests. After the helper extraction both share the cache
logic, so the tests largely duplicate batch coverage.

**Fix:** Delete the function and port any unique test assertions (forceFresh,
timeSource retention) to batch-based tests.

## 8. Gson → kotlinx.serialization (or at least one shared Gson)

**Where:** `core/.../api/TransitlandClient.kt:131-321` (11 private response data
classes), plus separate `Gson()` instances in `TransitlandClient`,
`AndroidFavoritesManager`, `AttributionStore`, `CliFavoritesManager`, `TileDataStore`.

**Problem:** ~120 lines of Gson-shaped DTO boilerplate; five independently constructed
Gson instances (config drift risk; reflection cost on watch hardware).

**Fix:** Smaller step: one shared configured Gson (e.g. `dev.catchthenext.json.Json`)
in core. Larger step: kotlinx.serialization with `@Serializable` DTOs — also removes
reflection, which matters for wear startup. Migrate persisted formats carefully:
favorites JSON, tile cache (`nearby_departures_v2` key), and sync payloads must stay
wire/disk compatible.

## 9. Shared alert rendering composable

**Where:** `phone/.../ui/AlertCard.kt:26-54`, `wear/.../ui/AlertItem.kt:17-39`.

**Problem:** Severity→color mapping and header/description/url layout logic duplicated;
adding a severity level means touching both. Material3 vs Wear Compose APIs prevent a
single composable, but the *logic* (color selection, text selection/truncation rules)
can live in shared-android with thin platform wrappers.

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
