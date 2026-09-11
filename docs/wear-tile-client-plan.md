# Wear tile + client responsiveness plan

Implementation plan for an agent. Scope is the **Android client** (wear module,
shared-android, core). The server-side fix (511 realtime rebuild happening
inside the request) is tracked separately and is *not* part of this plan, but
several items below are sized assuming it will land.

## 1. Background and measured baseline (2026-09-02)

Tapping the tile on a Galaxy Watch6 Classic opens `MainActivity` → `departures`
route, which shows a spinner until `DeparturesPipeline.computeState()` returns.

Measured from a desktop against production
(`GET /api/v2/rest/departures?onestop_ids=s-9q9hwp6yxb-mountainviewcaltrainstationnorthbound`):

| Path | TTFB |
|---|---|
| Uncached (server 511 RT snapshot older than 60 s) | 10.0 s |
| Cached (server response cache, 50 s TTL) | 0.7 s |
| `/healthz` (CGI Python startup only) | 0.65 s |

The server fix will bring the uncached path to ~1.3 s. Independently of that,
the client has its own contributors that this plan removes:

1. Location is resolved **before** the network call, serially. A last-known fix
   older than 2 min or coarser than 500 m triggers a fresh fix with an 8 s
   timeout (`LocationProvider.locate`, PASSIVE mode). Common on the watch when
   the phone is not nearby.
2. Two pipeline runs on open: `DeparturesViewModel.init` runs `computeState`,
   and `DeparturesScreen`'s `repeatOnLifecycle(RESUMED)` immediately calls
   `refresh(false)` again. `TransitlandClient.dedupe` collapses the HTTP call
   but the location step runs twice.
3. `quickCacheRead()` returns `null` if the cache is older than 60 s, so the
   user sees "Fetching departures…" instead of the last known departures.
4. Tile time labels ("5m") are frozen between tile requests; the tile only
   re-renders when the system calls `tileRequest` (60 s freshness interval,
   only while the tile is in the carousel) or after `requestUpdate`.
5. Departures that have already left stay on the tile until the next request.
6. Nothing warms the cache when the user swipes *to* the tile; the fetch only
   starts when the tile is actually requested.
7. OkHttp read timeout is 30 s, so a slow server pins the spinner for 30 s.

## 2. Ground rules for this codebase

- Business logic lives in `shared-android` (or `core`) as pure functions /
  injectable lambdas; Android wiring in `departuresPipeline()`,
  `SharedViewModels.kt`, and the wear/phone modules. Keep it that way so the
  JVM tests (`./gradlew :shared-android:testPlayDebugUnitTest`, JUnit 5)
  cover the new logic. Use `FakeFavoritesManager`, no Robolectric.
- `DeparturesViewModel`, `DeparturesPipeline`, and `TileDataStore` are shared
  with the **phone** app (`phone/.../DeparturesScreen.kt`,
  `phone/.../widget/ClosestFavoriteWidget.kt`). Every change to them must keep
  the phone building and behaving sensibly.
- `LocationProvider` has two identical twins, `shared-android/src/play/...`
  and `shared-android/src/fdroid/...`. Change both, identically.
- Client/server tuning constants live in `Tuning.kt` with a mirror in
  `server/config.py`; keep comments there in sync.
- Build / test / deploy:
  - `./gradlew :shared-android:testPlayDebugUnitTest :wear:testDebugUnitTest`
  - `./gradlew :wear:assembleDebug :phone:assemblePlayDebug`
  - `./deploy_to_device.sh watch <ip> <port>` (adb at
    `~/android-sdk/platform-tools/adb`; watch IP/port changes per session).
  - Logcat tag for the pipeline is `Departures`.

## 3. Verified API surface (ProtoLayout 1.2.0 / Tiles 1.4.0, already on classpath)

Checked with `javap` against the jars in the Gradle cache. All of these are
usable **without upgrading**:

```
DynamicBuilders.DynamicInstant.platformTimeWithSecondsPrecision(): DynamicInstant
DynamicBuilders.DynamicInstant.withSecondsPrecision(java.time.Instant): DynamicInstant
DynamicInstant.durationUntil(DynamicInstant): DynamicDuration
DynamicDuration.toIntMinutes(): DynamicInt32        // truncates toward zero, like (ms / 60_000)
DynamicInt32.lte(int) / lt(int) / gte(int): DynamicBool
DynamicInt32.format(): DynamicString
DynamicString.constant(String); DynamicString.concat(DynamicString)
DynamicString.onCondition(DynamicBool).use(String).elseUse(String|DynamicString)
TypeBuilders.StringProp.Builder(staticValue).setDynamicValue(DynamicString).build()
TypeBuilders.StringLayoutConstraint.Builder("00m").build()
androidx.wear.protolayout.material.Text.Builder(Context, StringProp, StringLayoutConstraint)
TimelineBuilders.TimelineEntry.Builder().setLayout(..).setValidity(TimeInterval)
TimelineBuilders.TimeInterval.Builder().setStartMillis(..).setEndMillis(..)
TileService.onTileEnterEvent(EventBuilders.TileEnterEvent)   // deprecated in Tiles 1.5, see §9
TileService.onTileLeaveEvent(EventBuilders.TileLeaveEvent)
TileService.getUpdater(Context).requestUpdate(Class)
```

Timeline semantics (from the Android docs): when several entries' validity
periods overlap, the renderer shows the one with the **shortest remaining
validity**; an entry with no validity is the fallback once all others expire.
Dynamic expressions need renderer schema 1.2+ (Wear OS 4+; Galaxy Watch6 is
fine). Older renderers show the `StringProp` static value, so always supply a
sensible static fallback.

## 4. Work packages and order

Do these as separate, reviewable commits/PRs in this order. Each package
lists files, exact changes, tests, and acceptance criteria.

| # | Package | Risk | Depends on |
|---|---|---|---|
| A | ViewModel/UI quick wins (items 2, 3, and app-screen ticking) | low | – |
| B | Tile dynamic countdown labels (item 4) | low | – |
| C | Tile timeline entries with validity (item 5) | low | B |
| D | Fetch on tile entry + single refresh path (item 6) | low | – |
| E | Location in parallel with network (item 1) | medium | A |
| F | Network timeouts (item 7) | low | server fix |
| G | Library upgrade to Tiles 1.6 / ProtoLayout 1.4 (§9) | medium | B, C, D |

---

## 5. Package A — ViewModel and screen quick wins

### A1. Stale cache first, spinner only when there is nothing to show

**Files:** `shared-android/.../tile/DeparturesPipeline.kt`,
`shared-android/.../tile/Tuning.kt`, `wear/.../ui/DeparturesScreen.kt`,
`phone/.../ui/DeparturesScreen.kt` (check only).

- Add `Tuning.QUICK_CACHE_MAX_AGE_MS = 6 * 60 * 60 * 1000L`.
- Change `DeparturesPipeline.quickCacheRead()`:
  - Keep the `NoPermission` / `NoFavorites` early returns.
  - Include cached stops whose `fetchedAt` is within `QUICK_CACHE_MAX_AGE_MS`
    (instead of `< CACHE_TTL_MS`), still filtering departures to
    `currentMinutes() >= 0`. Set `isStale = now - fetchedAt >= CACHE_TTL_MS`
    on each `StopWithDepartures`.
  - Return `null` only when no usable stop remains.
- `DeparturesViewModel.init` already emits the quick state then the computed
  state. Make the quick emission carry `isRefreshing = true`
  (`DeparturesUi.Loaded(quick, isRefreshing = true)`) so the small top
  spinner shows while the network run is in flight.
- Wear `DeparturesReadyContent` already shows `freshnessLabel(state.fetchedAt)`;
  leave that as the staleness cue. Optionally tint it warning-colour when
  any stop `isStale`.
- Phone `ClosestFavoriteWidget` uses `quickCacheRead()` as a fallback when
  `computeState` throws; the widened window is an improvement there. No change.

**Tests** (`DeparturesPipelineTest`):
- Rename/replace `quickCacheRead returns null when only stale entries cached`
  with: stale-but-recent entry → `Ready` with `isStale = true`.
- New: entry older than `QUICK_CACHE_MAX_AGE_MS` → `null`.
- New: cached departures already in the past are filtered out; if none remain
  → `null`.

**Accept:** opening the app from the tile within a few hours of a successful
fetch shows departures immediately with the "Nm ago" label and a small
spinner; the full-screen "Fetching departures…" appears only on a cold cache.

### A2. Collapse the double run on open

**Files:** `shared-android/.../ui/DeparturesViewModel.kt`,
`DeparturesViewModelTest.kt`.

- Add to the VM: `private val runMutex = Mutex()` and
  `@Volatile private var lastCompletedAt = 0L`, plus
  `Tuning.VM_REFRESH_DEBOUNCE_MS = 15_000L`.
- `refreshInternal(force)`:
  - If `!force` and `runMutex.isLocked` → return (a run is in flight; the UI
    will get its result).
  - If `!force` and `now - lastCompletedAt < VM_REFRESH_DEBOUNCE_MS` → return.
  - Otherwise `runMutex.withLock { ... computeState(force) ...; lastCompletedAt = now }`.
- The `init` block's first run should go through the same path so
  `lastCompletedAt` is set. The `favoritesCountFlow` collector should call
  `refreshInternal(force = true)` semantics-wise "bypass debounce but not
  cache" — add a private `bypassDebounce` parameter rather than overloading
  `force` (which also bypasses the departures cache).
- Inject a `now: () -> Long` constructor parameter (default
  `System::currentTimeMillis`) for tests.

**Tests:**
- Two `refresh(false)` calls within the debounce window → `computeState`
  invoked once.
- `refresh(true)` during the window → invoked again.
- Favorites count change during the window → invoked again.

**Accept:** logcat shows exactly one `computeState force=false ...` line per
app open (was two).

### A3. Make the app screen tick

**Files:** `wear/.../ui/DeparturesScreen.kt`, `phone/.../ui/DeparturesScreen.kt`.

- `DeparturesReadyContent` computes `groupDepartures(...)` once per
  composition using wall-clock `currentMinutes()`. Add a `now` state that
  advances on each minute boundary:

  ```kotlin
  var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
  LaunchedEffect(Unit) {
      while (true) {
          delay(60_000L - (System.currentTimeMillis() % 60_000L))
          now = System.currentTimeMillis()
      }
  }
  val groups = remember(state, now) { groupDepartures(stops = state.stops, filter = { it.currentMinutes() in 0..59 }) }
  ```
  `groupDepartures` reads the clock internally, so keying `remember` on `now`
  is enough. Do the same in the phone screen and for `freshnessLabel`.

**Accept:** leave the departures screen open across a minute boundary; labels
decrement without a fetch.

---

## 6. Package B — Tile countdown via dynamic expressions

**Files:** `shared-android/.../tile/TileState.kt` (`GroupedDepartureTime`,
`groupDepartures`), `wear/.../tile/ClosestStopTileService.kt`,
new `wear/.../tile/DynamicLabels.kt`, `TileStateTest.kt`.

### B1. Carry the departure instant into the grouped model

- Add `val departureEpochMillis: Long` to `GroupedDepartureTime`. Populate it
  in `groupDepartures` from `CachedDeparture.departureEpochMillis`. Keep
  `minutes` (used for sorting, static fallback, and the phone/app screens).
- Update the `groupDepartures` tests that construct `GroupedDepartureTime`
  directly, and add an assertion that `departureEpochMillis` round-trips.

### B2. Dynamic label builder

New file `wear/.../tile/DynamicLabels.kt`:

```kotlin
/** "Now" when ≤ 0 minutes remain, else "<n>m"; recomputed by the renderer every second. */
fun countdownStringProp(departureEpochMillis: Long, staticMinutes: Long): TypeBuilders.StringProp {
    val target = DynamicBuilders.DynamicInstant.withSecondsPrecision(Instant.ofEpochMilli(departureEpochMillis))
    val minutes = DynamicBuilders.DynamicInstant.platformTimeWithSecondsPrecision()
        .durationUntil(target)
        .toIntMinutes()
    val label = DynamicBuilders.DynamicString.onCondition(minutes.lte(0))
        .use("Now")
        .elseUse(minutes.format().concat(DynamicBuilders.DynamicString.constant("m")))
    return TypeBuilders.StringProp.Builder(timeLabel(staticMinutes))
        .setDynamicValue(label)
        .build()
}

val COUNTDOWN_LAYOUT_CONSTRAINT: TypeBuilders.StringLayoutConstraint =
    TypeBuilders.StringLayoutConstraint.Builder("00m").build()   // widest expected value
```

Semantics match today's `timeLabel((dep - now) / 60_000)`: integer division
toward zero, "Now" for the minute either side of the instant. Values beyond
59 min are already excluded by `currentMinutes() in 0..59`-style filters on
the tile (`groupDepartures` default filter is `>= 0`; add a `<= 59` bound in
the tile's call to match the app screen).

### B3. Use it in the tile

In `ClosestStopTileService.groupedDepartureRow`, replace

```kotlin
Text.Builder(this, timeLabel(time.minutes))
```
with
```kotlin
Text.Builder(this, countdownStringProp(time.departureEpochMillis, time.minutes), COUNTDOWN_LAYOUT_CONSTRAINT)
```
keeping typography and colour. Leave the route label and the "Updated h:mma"
secondary label static (optionally make the latter a dynamic "Updated Nm ago"
with the same pattern; not required).

### B4. Renderer-version guard (cheap insurance)

`requestParams.deviceConfiguration.rendererSchemaVersion` exposes
`major`/`minor`. If `major < 1 || (major == 1 && minor < 200)` fall back to
the static `Text.Builder(this, timeLabel(...))`. Put the check in one helper
so it can be deleted after §9.

**Tests:** the builder returns a `StringProp` whose `value` equals the static
label (JVM test in the wear module; ProtoLayout builders are plain Java and
run on the JVM). Dynamic evaluation itself is only verified on-device.

**Accept (on watch):** put the watch in airplane mode with a fresh tile; the
"Nm" values count down each minute without any `tileRequest` log line.

---

## 7. Package C — Timeline entries so departed trips drop off

**Files:** new `shared-android/.../tile/TileTimeline.kt` (pure),
`wear/.../tile/ClosestStopTileService.kt`, new `TileTimelineTest.kt`,
`Tuning.kt`.

### C1. Pure boundary computation

```kotlin
data class TimelineSlice(
    /** Departures still in the future at this slice; render from these. */
    val stops: List<StopWithDepartures>,
    /** Wall-clock millis when this slice stops being valid; null for the terminal slice. */
    val validUntilMillis: Long?,
)

/**
 * One slice per distinct displayed departure instant, in ascending order, each
 * valid until that departure plus [nowGraceMs]; plus a terminal slice with the
 * remaining (possibly empty) departures and no expiry. Capped at [maxSlices].
 */
fun buildTimelineSlices(
    state: TileState.Ready,
    now: Long,
    maxGroups: Int = Tuning.TILE_MAX_GROUPS,
    maxPerGroup: Int = 3,
    maxSlices: Int = Tuning.TILE_MAX_TIMELINE_SLICES,
    nowGraceMs: Long = Tuning.TILE_NOW_GRACE_MS,
): List<TimelineSlice>
```

Algorithm:
1. Compute the *displayed* departures: `groupDepartures(state.stops, maxPerGroup)
   .take(maxGroups)` flattened → their `departureEpochMillis`, distinct, sorted.
2. For each boundary `b_i` (up to `maxSlices - 1`): slice stops =
   `state.stops` with departures filtered to `departureEpochMillis >= b_i`;
   `validUntilMillis = b_i + nowGraceMs`. (Departures at exactly `b_i` are
   still shown as "Now" until the grace expires; matches `currentMinutes() >= 0`.)
3. Terminal slice: departures with `departureEpochMillis > last boundary`
   (empty list is fine) and `validUntilMillis = null`.
4. Every slice's `stops` list must keep `fetchedAt`, `alerts`, `isStale`, and
   the `Stop` untouched; only `departures` is filtered.

Add `Tuning.TILE_MAX_TIMELINE_SLICES = 8` and `Tuning.TILE_NOW_GRACE_MS = 60_000L`.

### C2. Emit the timeline

In `tileRequest`, for `TileState.Ready`:

```kotlin
val timeline = TimelineBuilders.Timeline.Builder()
buildTimelineSlices(state, now).forEach { slice ->
    val layout = if (slice.stops.all { it.departures.isEmpty() })
        simpleLayout(deviceParams, "No departures", "in the next hour")
    else readyLayout(TileState.Ready(slice.stops, state.fetchedAt), deviceParams)
    val entry = TimelineBuilders.TimelineEntry.Builder()
        .setLayout(LayoutElementBuilders.Layout.Builder().setRoot(tappableLayout(layout)).build())
    slice.validUntilMillis?.let {
        entry.setValidity(TimelineBuilders.TimeInterval.Builder().setEndMillis(it).build())
    }
    timeline.addTimelineEntry(entry.build())
}
```

Only `setEndMillis` is needed: all slices are valid from now, and the renderer
picks the one with the shortest remaining validity, which is exactly the
earliest-expiring slice. Non-`Ready` states keep the single-entry timeline.

`readyLayout` currently calls `groupDepartures(state.stops)` itself; it will
naturally render only the slice's remaining departures. Keep
`setFreshnessIntervalMillis(TILE_FRESHNESS_INTERVAL_MS)` unchanged (a fetch is
still needed for realtime delay updates; see §10 on cadence).

**Tests (`TileTimelineTest`, JVM):**
- Three departures at +3, +8, +8, +15 min → 3 boundaries + terminal = 4 slices;
  slice 0 contains all, slice 1 drops the +3, slice 2 drops the +8s, terminal is
  empty with `validUntilMillis == null`.
- `validUntilMillis == epoch + nowGraceMs`.
- Cap: 20 distinct departures with `maxSlices = 8` → 8 slices, terminal slice
  holds departures beyond the 7th boundary.
- Departures beyond `maxGroups`/`maxPerGroup` (not displayed) create no
  boundary.
- Alerts / `isStale` / `fetchedAt` preserved on every slice.

**Accept (on watch):** with the tile visible and no network, a departure
showing "Now" disappears about a minute after its time, and the next one moves
up, without a `tileRequest` log line.

---

## 8. Package D — Fetch when the user swipes to the tile, single refresh path

**Files:** `wear/.../tile/ClosestStopTileService.kt`, `Tuning.kt`.

### D1. One refresh helper

Extract the "cache is stale → fetch → requestUpdate" logic out of
`tileRequest` into:

```kotlin
private val refreshInFlight = AtomicBoolean(false)

private fun refreshIfStale(reason: String) {
    refreshScope.launch {
        val dataStore = TileDataStore(this@ClosestStopTileService)
        val cache = dataStore.read()
        val now = System.currentTimeMillis()
        val stale = cache.nearbyDepartures.isEmpty() ||
            cache.nearbyDepartures.any { now - it.fetchedAt > Tuning.TILE_REFRESH_THRESHOLD_MS }
        if (!stale || !refreshInFlight.compareAndSet(false, true)) return@launch
        try {
            Log.d("Departures", "tile refresh ($reason)")
            doFetchState(dataStore)
            TileService.getUpdater(this@ClosestStopTileService).requestUpdate(ClosestStopTileService::class.java)
        } finally {
            refreshInFlight.set(false)
        }
    }
}
```

`tileRequest` keeps its current shape (render from cache, block only when the
cache is empty) but calls `refreshIfStale("tileRequest")` instead of its
inline `refreshScope.launch`. The `AtomicBoolean` prevents the enter event and
the tile request from running the pipeline (and the location lookup) twice;
`TransitlandClient.dedupe` only covers the HTTP call.

### D2. Enter event

```kotlin
override fun onTileEnterEvent(requestParams: EventBuilders.TileEnterEvent) {
    super.onTileEnterEvent(requestParams)
    refreshIfStale("enter")
}
```

Horologist's `SuspendingTileService` is a plain `TileService` subclass, so the
override is available. `onTileEnterEvent` is deprecated from Tiles 1.5 and
stops firing for apps targeting API 36; §9 migrates it to
`onRecentInteractionEvents`. Do not bump `targetSdk` to 36 before §9.

### D3. Cold-start path

When `cache.nearbyDepartures.isEmpty()`, `tileRequest` currently blocks on a
full fetch (up to 10 s+ today; the system may time the tile out). Change it to
render `simpleLayout("Loading departures…")` immediately, then let
`refreshIfStale("cold")` fetch and `requestUpdate`. Keep the blocking fetch
only if `buildStateFromCache` returns null *and* favorites are non-empty *and*
a cached location exists — i.e. never; remove the `cachedState ?: doFetchState`
fallback in favour of the async path plus the loading layout.

**Accept:** logcat shows `tile refresh (enter)` when swiping to the tile with a
stale cache, followed by one `computeState` and a re-render; swiping back and
forth within 30 s produces no second fetch.

---

## 9. Package E — Location no longer gates the network call

**Files:** `shared-android/.../tile/DeparturesPipeline.kt`,
`shared-android/src/play/.../location/LocationProvider.kt`,
`shared-android/src/fdroid/.../location/LocationProvider.kt`,
`shared-android/.../location/LocationApi.kt`, `Tuning.kt`,
`DeparturesPipelineTest.kt`.

### E1. Split "quick" and "fresh" location

Add to `LocationApi.kt`:

```kotlin
/** Best-effort location that must return fast: in-memory cache or last-known fix, never a fresh fix. */
fun interface QuickLocationProvider { suspend fun quickLocation(): LatLon? }
```

In both `LocationProvider` twins add `suspend fun quickLocation(): LatLon?`
that returns `LocationCache.get()` or a usable last-known fix, else `null`,
without calling `fetchFresh`. Keep `locate(PASSIVE)` as is (it is what
eventually refreshes the cache). Reduce the PASSIVE `fetchFresh` timeout from
8 s to `Tuning.PASSIVE_LOCATION_TIMEOUT_MS = 5_000L` (both twins).

### E2. Pipeline: fetch with the best location available now, refine after

`DeparturesPipeline` gets a new collaborator
`quickLocation: suspend () -> LatLon?` (default `{ null }` so existing tests
and the phone widget keep working) and `computeState` becomes:

1. `favorites`, `hasPerm`, `cache`, `threshold` as today.
2. `startLoc = quickLocation() ?: cache.lat/lon` (if permission).
3. If `startLoc == null` → today's behaviour: await `currentLocation()`; if
   still null → `NoLocation`.
4. Otherwise run **concurrently** (`coroutineScope { async {} }`):
   - `fetchDeferred`: `computeTileState(favorites, startLoc, ...)` → state.
   - `freshDeferred`: `currentLocation()` (may take up to 5 s; may be null).
5. Await `fetchDeferred`; publish that as the state candidate.
6. Await `freshDeferred`. If non-null, `updateCachedLocation(fresh)`. Compute
   the stop selection for `fresh` using the same `withinMeters(...).take(max)` /
   `closestTo` logic (extract `selectStops(favorites, lat, lon, threshold, maxStops)`
   from `updateNearbyStopsDepartures` into a pure, tested function). If the
   selected stop ids differ from those used in step 4, run
   `computeTileState` again for `fresh` (its per-stop cache logic will reuse
   anything just fetched) and return that instead.
7. Stale-stop resolution runs on whichever state is returned, as today.

The extra `log(...)` lines should say which location source was used
(`quick`, `cache`, `fresh`) and whether a second fetch was needed.

Wire `quickLocation = { locationProvider.quickLocation() }` in
`departuresPipeline()`.

**Tests (`DeparturesPipelineTest`):**
- `quickLocation` non-null and `currentLocation` slow (use `delay` under
  `runTest`) → batch fetch is issued before `currentLocation` completes
  (record ordering with a list of events).
- Fresh location selects the same stops → exactly one batch call.
- Fresh location selects different stops → two batch calls; returned state is
  for the fresh selection.
- `currentLocation` returns null → state from the quick location is returned
  and cached location is not overwritten.
- No quick location and no cache → behaviour identical to today
  (existing tests must still pass).
- New `selectStops` unit tests (move the two existing threshold/closest tests
  in `TileStateTest` onto it).

**Accept:** on the watch with the phone away (stale last-known fix), logcat
shows the batch request starting within ~100 ms of `computeState`, not after
the location wait.

---

## 10. Package F — Timeouts and request cadence

**Files:** `core/.../api/TransitlandClient.kt`, `shared-android/.../di/CommonGraph.kt`,
`wear/.../WearGraph.kt`, `phone/.../PhoneGraph.kt` (or wherever the phone
graph subclasses `CommonGraph`), `Tuning.kt`.

- Add `connectTimeoutMs`, `readTimeoutMs`, `callTimeoutMs` constructor
  parameters to `TransitlandClient` (defaults = today's 15 s / 30 s / none) and
  plumb them through `CommonGraph` so each app can set its own. Set OkHttp
  `callTimeout` so the whole call, not just each read, is bounded.
- Wear values: connect 10 s, read 20 s, call 25 s **now**; drop to
  10 / 12 / 15 s once the server's uncached path is under ~2 s. Put the
  wear values in `Tuning` with a comment referencing the server change.
- `Tuning.TILE_FRESHNESS_INTERVAL_MS` stays at 60 s (matches 511's RT cadence)
  but note in the `Tuning` comment that it must stay ≥ the server's
  `RESPONSE_CACHE_TTL` (currently 50 s) for consecutive tile polls to hit the
  server cache; the server side should raise its TTL to 60 s or more.

**Tests:** `TransitlandClientTest` gains a case that a `MockWebServer`
response slower than `callTimeoutMs` throws `IOException` promptly.

---

## 11. Package G — Library upgrade path

Do this **after** B–D are merged and verified; it is a separate PR.

Targets (checked against the AndroidX release pages, Sept 2026):

| Library | Now | Target | Notes |
|---|---|---|---|
| `androidx.wear.tiles:tiles` | 1.4.0 | 1.6.2 | needs compileSdk 35 (already), minSdk ≥ 23 (we are 30) |
| `androidx.wear.tiles:tiles-material` | 1.4.0 | **remove** | deprecated since 1.2; code already uses `protolayout-material` |
| `androidx.wear.protolayout:protolayout` / `protolayout-material` | 1.2.0 | 1.4.2 | required pairing for tiles 1.6.x |
| `com.google.android.horologist:horologist-tiles` | 0.6.14 | keep, or drop for `Material3TileService` | see below |

Steps:
1. Bump `wearTiles`, `protolayout` in `gradle/libs.versions.toml`; delete the
   `wear-tiles-material` alias and its `implementation` line. Build.
2. Replace `onTileEnterEvent` (D2) with
   `onRecentInteractionEvents(events: List<EventBuilders.TileInteractionEvent>)`
   and treat any `ENTER` event as the trigger for `refreshIfStale("enter")`.
   This is mandatory before `targetSdk = 36`.
3. Delete the renderer-version guard from B4.
4. Re-run the on-device checks from B, C, D. Wear OS 6 renders all tiles in
   the system font; re-check `TileFit`'s line-height table and
   `AVERAGE_GLYPH_EM` against the new `Typography` (§15) so the group count
   and headsign trim still fit on the Watch6 Classic.
5. Optional follow-up: migrate `ClosestStopTileService` from Horologist's
   `SuspendingTileService` to `Material3TileService` (single `suspend`
   `tileResponse`, `ProtoLayoutScope` inlines resources so
   `resourcesRequest` and `ALERT_ICON_ID` mapping go away, and the docs cite
   faster tile loading). If Horologist 0.6.x fails to resolve against
   tiles 1.6 (it declares a tiles dependency itself), do this migration in the
   same PR and drop `horologist-tiles`; keep `horologist-compose-*` for the app
   screens.
6. `tileId`-scoped `requestUpdate` is available in 1.6; not needed while the
   app ships one tile.

---

## 12. Verification checklist (run after each package, and all at the end)

1. `./gradlew :shared-android:testPlayDebugUnitTest :wear:testDebugUnitTest :core:test`
   (the `DockerIntegrationTest` failure in `core` is pre-existing; everything
   else must pass).
2. `./gradlew :wear:assembleDebug :phone:assemblePlayDebug :phone:assembleFdroidDebug`
   — the fdroid build catches the `LocationProvider` twin drifting.
3. Deploy to the watch, then with `adb logcat -s Departures`:
   - Open the app from the tile: exactly one `computeState force=false` line;
     content visible before it prints (A1/A2).
   - Swipe to the tile after >30 s away: `tile refresh (enter)` then a
     re-render; swipe away/back within 30 s: no second fetch (D).
   - Airplane mode, tile visible: labels count down each minute; a "Now"
     departure disappears ~1 min after its time (B/C).
   - Phone away (stale fix): batch request logged before any location wait (E).
4. Phone app smoke test: departures screen and the home-screen widget still
   load and refresh (shared VM/pipeline changes).

## 13. Out of scope (tracked elsewhere)

- Server: serve stale RT and refresh out of band; native protobuf on the NFSN
  host; raise `RESPONSE_CACHE_TTL`; deploy the June→September server changes
  that are still only in the repo.
- Complications / ongoing-activity surfaces.

## 14. Location follow-up (packages H–L)

After A–F the tile's *times* refreshed snappily but its *stops* only changed
once the app was opened. Cause: `quickLocation()` returned null as soon as the
60 s `LocationCache` expired and the last-known fix was older than 2 min, so
`computeState` started from the position persisted by the last **app** run —
possibly hours old — while the one parallel balanced-power attempt (5 s) almost
never succeeds on a watch away from its phone, and the refresh scope was
cancelled in `onDestroy` the moment the tile host unbound.

- **H — Instrumentation.** `computeState` logs its start source with an age
  (`locSource=quick age=42s`) and the outcome of the parallel fix
  (`fresh fix ok in <ms>ms` / `fresh fix none after <ms>ms`); both
  `LocationProvider` twins log each `fetchFresh` (provider/priority, elapsed,
  and the exception path that `runCatching` used to swallow). Tag `Departures`.
- **I — Newest-wins start location.** `TileDataStore` persists
  `cached_loc_at` alongside the position; `quickLocation()` returns a
  `LocationFix` (position + wall clock) built from `LocationCache.getFix()` or
  the last-known fix at *any* age (still bounded by accuracy, and only seeded
  into the 60 s cache when it passes the 2 min `isUsable()` test, so a stale fix
  never short-circuits `locate(PASSIVE)`). `computeState` starts from whichever
  of the quick and persisted fixes is newer; an untimestamped persisted
  position (pre-upgrade installs) loses to any quick fix.
- **J — Render decoupled from the fix.** `computeState(forceFresh,
  onIntermediate)` publishes the first result as soon as the batch call
  returns, and only when a refine pass is actually pending. The tile calls
  `requestUpdate()` from the callback and again only if the final state is a
  different object; `DeparturesViewModel` publishes it as
  `Loaded(state, isRefreshing = true)`. With no render waiting on it,
  `PASSIVE_LOCATION_TIMEOUT_MS` rises 5 s → 15 s.
- **K — Refresh survives the service instance.** The scope and the in-flight
  flag moved to the process-wide `TileRefresher`; `onDestroy` no longer cancels
  anything and the refresh uses `applicationContext` throughout. This does not
  grant background location (no `ACCESS_BACKGROUND_LOCATION`), but a fix that
  *does* arrive is persisted for the next tile request and the departures write
  is never cancelled mid-flight. The shared flag also closes the gap where a
  recreated service could start a second pipeline run.
- **L — Rate-limited GPS fallback (wear only).**
  `LocationProvider(context, gpsFallback = true)` tries one
  `PRIORITY_HIGH_ACCURACY` fix (`GPS_FALLBACK_TIMEOUT_MS`, 15 s) after the
  balanced attempt comes back empty, but only when no last-known fix is younger
  than `GPS_FALLBACK_MIN_FIX_AGE_MS` (10 min, read from the OS so it survives
  process death) and not more often than `GPS_FALLBACK_MIN_INTERVAL_MS`
  (5 min). Wear passes `true` from `WearViewModelFactory` and the tile service;
  phone keeps `false`. The fdroid twin gained a provider choice so its balanced
  path uses `NETWORK_PROVIDER` and only the fallback/HIGH paths use GPS,
  matching play. Worst case per tile refresh: 15 s balanced + 15 s GPS, all
  after the first render, at most once per 5 minutes.

Device checks (with `adb logcat -s Departures`): after >10 min with the app
closed and having moved, the tile logs `locSource=quick age=…` and fetches
immediately (I); the first `requestUpdate` precedes the `fresh fix …` line, and
a selection change logs `refetching for fresh location` then a second render
(J); swiping away within ~1 s still logs `computeState result=` (K); outdoors
with the phone out of range and WiFi off, `fresh fix none after 15000ms` is
followed by `gps fallback: attempting`, and a second visit within 5 min shows
no further fallback line (L).

## 15. Package M — per-device fit budget (2026-09-10)

Symptom: on smaller screens, or with a larger system font, three two-line
groups plus the stop-name header overran `PrimaryLayout`'s content slot, and
the "Updated h:mma" secondary label was clipped or pushed off the bottom.

ProtoLayout has no fit-to-screen primitive, so the tile now sizes itself
before building the layout. `wear/.../tile/TileFit.kt` is pure arithmetic over
`DeviceParameters` (width, height, shape, font scale) and is unit-tested for
every real screen size the app ships to (192–240 dp) at font scales 0.85–1.5:

- **Content band.** A centred box of fixed size. Round: ±0.31·D tall, width =
  chord of the circle at the band edge minus a bezel inset, so start-aligned
  rows never run under the bezel. Square: 80 % × 90 %. The band is shrunk
  further if the footer slot would otherwise collide with it.
- **Footer.** "Updated h:mma" lives in a sibling box anchored to the bottom of
  the root, not in the content column, so nothing the content does can move
  it. `PrimaryLayout` is no longer used by the tile.
- **Group count.** `maxGroups(hasHeader)` = floor of band height over
  (36 sp × fontScale + 4 dp spacer), minus the header line, clamped to
  1..`Tuning.TILE_MAX_GROUPS`. The same count is passed to
  `buildTimelineSlices` so invisible groups do not create timeline boundaries.
- **Route label.** `routeLabel()` trims the headsign to a character budget
  from the band width and BODY2's 14 sp (0.55 em average glyph), drops it
  below 4 characters, and subtracts the alert icon when shown. The stop tag
  moved from the label row to the end of the times row, where there is spare
  width and the renderer's end-ellipsis hits the tag rather than a time.

Line heights (BODY2/CAPTION1 18 sp, CAPTION2 16, CAPTION3 14) were read out
of protolayout-material 1.2.0's `Typography`; re-verify on the §11 bump.

Device check: on the Watch6 Classic, a single-stop tile with three routes
shows the stop name, three groups and the Updated label with clear space
between them; with the system font at the largest setting the tile drops to
two groups and the Updated label is still fully visible. On a 41 mm Pixel
Watch (or the 192 dp emulator) the same data shows two groups.
