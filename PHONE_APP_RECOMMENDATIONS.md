# Phone App Recommendations

**Scope:** The phone app in `phone/`, analyzed 2026-07-17.

**Product focus:** Catch The Next is for trips the user has taken many times.
There is deliberately **no trip planning / routing** — apps like Transit,
Citymapper, and Google Maps already do that, and a habitual rider doesn't need
it. What a habitual rider needs is: *when is my usual ride leaving, from my
usual stop, and do I need to leave now.* Every recommendation below is filtered
through that lens. Features that mainly serve trip *discovery* (full maps,
journey search, multi-modal comparison) are explicitly out of scope or demoted.

The current state: the bones are good — nearby departures grouped by route,
favorites with watch sync, place/stop search, a Live Update tracking
notification with geofence-style auto-dismiss, and Glance widgets. But several
surfaces still read like a debug console, and the features that serve the
habitual-rider use case best (filtering, widgets, leave-by timing) are either
hidden or minimal.

---

## Priority 1 — the habitual-rider core

These are the features where "I've taken this trip 100 times" changes what the
UI should be. They are the app's identity; competitors are weakest here because
they optimize for first-time trips.

> **Batches 2 & 3 (1.1, 2.6, 1.2, 1.4) — DONE (2026-07-17).** Summary of what
> shipped:
> - `Stop` gained four optional fields — `routesServed`, `nickname`,
>   `shownRoutes`, `sortOrder` — all nullable so stored favorites JSON and the
>   watch sync payload stay backward compatible. Personal fields survive
>   stale-stop re-resolution (`resolveStaleStops` copies them onto the
>   replacement stop).
> - **1.1**: `groupDepartures` now drops departures not in the favorite's
>   `shownRoutes` (null/empty = show all), so the phone list, both widgets, and
>   the wear tile all inherit the filter. Editor: FilterList icon in stop
>   details (checkbox dialog; all-checked stores null so new routes stay
>   visible). Persisted via new `FavoritesManager.updateFavorite` (default impl
>   in the interface; `SyncedFavoritesManager` overrides to bump only the
>   touched entry's logical clock).
> - **2.6**: server attaches `routes_served` to nearby-stop responses — local
>   511 path via a per-stop static-DB query (parents aggregate child
>   platforms), Transitland path from `route_stops`. `StopList` now shows
>   distance from search origin + routes served.
> - **1.4**: nickname (rename dialog in stop details, favorite row shows
>   nickname headline / GTFS name as supporting text) and manual order (drag
>   handle on favorite rows; order stamped into `sortOrder`, favorites getters
>   sort via `inFavoriteOrder()` in both managers). Both sync to the watch
>   automatically since the whole `Stop` is the sync payload.
> - **1.2**: both Glance widgets now render real departures (route + colored
>   live/scheduled countdowns + freshness label) instead of a bus emoji;
>   resized 1x1 → 3x2. `FavoriteStopWidget` fetches its configured stop through
>   the shared batch-fetch/cache helper; `ClosestFavoriteWidget` reuses
>   `departuresPipeline.computeState`. Refresh: `WidgetRefreshWorker`
>   (WorkManager, 15 min, scheduled in `PhoneApp`) + a "↻" corner tap; whole-
>   widget tap still starts Live Update tracking.
> - Also fixed along the way: `StopDetailsViewModel.toggleFavorite` now uses
>   the stop-based `removeFavorite` (the null-`onestopId` bug flagged in the
>   batch-1 notes).
>
> Lessons learned / gotchas for later batches:
> - The 511 static DB **prunes stop_times to the active service window at
>   ingest**, so any test needing routes-at-a-stop must build the fixture with
>   an in-window service date (2099 rows vanish silently).
> - `test_proxy.py` pins the exact key set of the local stop shape — adding a
>   response field means updating that test (now 14 keys).
> - Glance widgets: load data in `provideGlance` *before* `provideContent`;
>   `update()`/`updateAll()` re-runs the whole `provideGlance`, which is what
>   the refresh worker and the ↻ action rely on.
> - Reordering favorites lives in a screen-local list during the drag (synced
>   from the store when idle) because DataStore writes round-trip async and
>   would fight the gesture.
> - `DockerIntegrationTest` still fails without the docker server (pre-
>   existing, unrelated).

### 1.1 Route/direction filtering per favorite ✅ DONE

**The single most valuable feature for this product.** A regular rider at a
busy stop (Muni/AC Transit trunk stops serve 5+ lines, both directions) cares
about one or two routes in one direction. Today every departure at a favorite
stop is shown, so the signal is buried in a wall of cards.

- Add a per-favorite "shown routes" set, editable from stop details
  (checkbox list of route short names seen at that stop).
- Default: all routes shown; once the user unchecks any, only checked routes
  appear.
- Apply the filter in the shared grouping layer (`groupDepartures`) so the
  phone list, the widgets, **and the wear tile** all benefit — this is where
  the phone app earns its keep as the configuration surface for the watch.
- Persist in the favorites store and include it in watch sync.

This is Transit's "pinned lines" feature, but it fits this app even better:
for a known trip, the filter is set once and never touched again.

### 1.2 Widgets that show departure times ✅ DONE

`FavoriteStopWidget` currently shows a bus emoji and the stop name — it's a
launcher shortcut, not a widget. For a habitual rider, a glanceable home-screen
widget is arguably *more* important than the app itself: the whole trip is
known, only the number matters.

- Show the next 2–3 departure times for the configured stop (respecting the
  route filter from 1.1), reusing `groupDepartures`/`timeLabel`/live-vs-scheduled
  coloring from the shared tile code.
- Refresh via WorkManager (~15 min) plus refresh-on-tap; show a staleness label
  (`freshnessLabel` already exists).
- Same treatment for `ClosestFavoriteWidget`: "closest stop + next times" is
  the phone analog of the wear tile and should look like it.
- Keep the current tap action (start Live Update tracking) — a times-showing
  widget that also launches tracking in one tap is a genuinely competitive
  surface.

### 1.3 "Leave now" nudges ✅ DONE

The inverse of the existing geofence logic is the feature habitual riders
actually want: *"leave in 4 min to catch the 33."* `LiveUpdateService` already
has live location, distance-to-stop, and departure ETAs — the ingredients are
all there.

- While tracking: compute `walkMinutes = distanceMeters / ~80` and when
  `eta - walkMinutes` crosses a threshold (e.g. 2 min of slack), escalate the
  notification (heads-up / distinct sound).
- Optionally show "leave by 3:38" directly in the Live Update notification
  line rather than only the countdown.
- No routing engine needed — straight-line walking estimate with a fudge
  factor is fine for a stop the user walks to every day, and a per-favorite
  "walk time override" setting covers edge cases (crossing a highway, etc.).

### 1.4 Nicknames and manual ordering for favorites ✅ DONE

For known trips, GTFS names are noise: "Market St & 4th St" means nothing;
"Work → home" means everything.

- Add rename (long-press or from stop details); show nickname as headline,
  real stop name as supporting text.
- Manual reorder via drag handle — a habitual rider's mental order is
  morning-commute-first, not alphabetical or insertion order.
- Sync both through the existing favorites sync so the watch shows the same
  names/order.

### 1.5 Surface the tracking feature — it's currently hidden ✅ DONE

Live Update tracking is the differentiating feature, and today its only entry
point on a departure card is an unlabeled **long-press**
(`DeparturesScreen.kt` — `combinedClickable(onClick = {}, onLongClick = onTrack)`).
A user will never find it.

- Make **tap** on a departure card open stop details (currently a no-op).
- Add an explicit "Track" affordance: a small icon button on the card, or a
  tap-opened bottom sheet with *View stop / Track / Alerts*.
- Keep the toolbar toggle, but per-card entry is what matches the mental model
  ("track *this* departure").

---

## Priority 2 — quick fixes to things that feel broken

> **Batch 1 (2.1, 2.2, 2.3, 1.5) — DONE (2026-07-17).** Do not re-touch these
> items. Summary of what shipped:
> - New shared `DepartureRow` composable (`phone/ui/DepartureRow.kt`) renders a
>   raw `Departure` with a colored live/scheduled countdown + absolute clock
>   time; used by both `StopDetailsScreen` and `StopConfirmScreen` in place of
>   `Departure.displayString()` (2.1). `displayString()` is now unused by the
>   phone UI but kept for the CLI.
> - Absolute clock time (`clockTimeLabel` / `absoluteTimeLabel` in
>   `DepartureLabels.kt`) shown alongside the countdown once a departure is
>   ≥15 min out (2.3). For the grouped Departures list this required a new
>   `clockTime` field on `GroupedDepartureTime` (defaulted null, so the wear
>   tile is unaffected — the tile does **not** yet show absolute times; revisit
>   under 2.4 if desired).
> - `FavoritesScreen` delete is now swipe-to-dismiss + "Undo" snackbar (2.2).
>   Fixed the null-`onestopId` removal bug via a new
>   `FavoritesViewModel.removeFavorite(stop: Stop)` (falls back to id-based
>   rebuild) plus `restoreFavorite(stop)` for Undo. The old
>   `removeFavorite(onestopId: String)` is retained (still used by
>   `StopDetailsViewModel` and a unit test).
> - Departure cards: **tap now opens stop details** and there's an explicit
>   Track (bus) icon button per card; long-press still starts tracking (1.5).
>
> Discovered along the way (NOT done, candidates for later batches):
> - `StopDetailsViewModel.removeFavorite(current.stop.onestopId ?: "")` has the
>   same null-`onestopId` bug 2.2 fixed for favorites — worth the same
>   `removeFavorite(stop)` treatment.
> - The wear tile could reuse the new `clockTime` plumbing for absolute times
>   (ties into 2.4's readability goal).

### 2.1 Stop details and confirm screens render CLI-formatted strings ✅ DONE

`StopDetailsScreen` and `StopConfirmScreen` display
`Departure.displayString()` — the monospace-padded string from `core`
(`"12 mins  | Route 5 → Downtown LIVE"`). In a proportional-font `ListItem`
the padding collapses and it looks like debug output. The Departures tab
already has a proper `DepartureCard`; extract/reuse it (or a row variant) on
both screens. Biggest visual win per line of code changed.

### 2.2 One-tap favorite delete with no undo ✅ DONE

`FavoritesScreen` puts a delete `IconButton` on every row. Replace with
swipe-to-dismiss + snackbar Undo (or move delete into stop details). Also fix
the adjacent bug: `viewModel.removeFavorite(stop.onestopId ?: "")` means a
favorite with a null `onestopId` silently cannot be removed.

### 2.3 Show absolute times alongside relative ✅ DONE

Only "12 mins" is shown. For planning a known trip, the clock time matters
("the 3:42 one"). Show "12 min · 3:42 PM" past ~15–20 minutes out; the data
(`displayDepartureTime`) is already in the model. Consider a per-user setting
if the extra text feels noisy on the wear tile.

### 2.4 Configurable departure window ✅ DONE

`ReadyContent` hard-codes `currentMinutes() in 0..59`. Habitual riders of
infrequent lines (ferries, commuter buses that run every 40–60 min) will see
an empty screen. Make the window a setting or simply extend to 90–120 min with
absolute times (2.3) so the longer horizon stays readable.

### 2.5 Deep-link handler drops non-numeric ids ✅ DONE

`MainActivity` (`PhoneNavGraph` LaunchedEffect) only navigates when the deep
link's last segment parses as `Long`; a onestop-style string id is consumed
and silently discarded. Either resolve onestop ids to the internal stop id or
stop emitting such links.

### 2.6 Stop pickers show only names ✅ DONE

The nearby/search result list (`StopList`) shows just a stop name. Two
same-named stops on opposite sides of the street are indistinguishable. Add:

- distance from search origin (already computed for favorites), and
- **routes served** per stop (needs a small server/API addition, but is also
  the data 1.1 wants — one backend change serves both).

This is the trip-discovery surface trimmed to the minimum this product needs:
enough metadata to add the *right* stop once, after which the user rarely
returns here.

---

## Priority 3 — polish and hygiene

> **Batches 4 & 5 (1.3, 3.2, 2.4, 3.1, 3.4, 2.5) — DONE (2026-07-17).** 3.3
> deferred — see its entry below. Summary of what shipped:
> - `Stop` gained a fifth optional field, `walkMinutesOverride: Int?`, edited
>   from a new walk-icon button in stop details (`WalkTimeDialog`) and synced
>   like the other per-favorite fields.
> - **1.3**: `LiveUpdateService.runTracking` now tracks live distance from the
>   `locationJob`, estimates `walkMinutes` (override, else `distance / 80
>   m-per-min`), and computes `leaveByEpochMs` for the ongoing notification's
>   new "Leave by 3:38" line. When `eta - walkMinutes` drops to ≤2 min slack it
>   posts a one-shot heads-up notification on a **new, separate**
>   `leave_now_v1` channel (own sound) — the ongoing tracking channel stays
>   silent by design (`setOnlyAlertOnce`), so escalation needed its own channel
>   rather than fighting that.
> - **3.2**: `GroupedDeparture` gained `alertHeadline: String?` (first active
>   alert's header). Departure cards and favorite rows show it as a red
>   single-line, tap-to-open-alerts row. `LiveUpdateService`'s 60s refresh now
>   also pulls `stopResult.alerts` and prepends the headline to the Live Update
>   notification body.
> - **2.4**: window extended from 60 to 120 min (the doc's "simply extend"
>   alternative) rather than a new setting — absolute clock times (2.3) already
>   make the longer horizon readable, so a setting would add UI for little gain.
> - **3.1**: new `IntroScreen` composable shown once (flag in a plain
>   `SharedPreferences`, not DataStore — one-shot boolean, no need for a Flow);
>   permission request moved out of `onCreate` into a `requestMissingPermissions()`
>   called either immediately (returning user) or from the intro's Continue
>   button (first run). `TileState.NoFavorites` now navigates straight to
>   `"add"` instead of `"favorites"`.
> - **3.4**: `SegmentedButton`/`SingleChoiceSegmentedButtonRow` (M3) replaced
>   the Miles/Kilometers `Switch`.
> - **2.5**: the deep-link `LaunchedEffect` in `MainActivity` now falls back to
>   matching the onestop-id string against `favoritesManager.getFavorites()`
>   when it isn't a bare `Long` — no network round trip, so it only resolves
>   links pointing at an existing favorite (the only kind the app currently
>   emits, from the Live Update notification tap).
>
> Lessons learned / gotchas:
> - Gradle module task names are flavor-qualified here (`play`/`fdroid`), not
>   plain `compileDebugKotlin` — use e.g. `:phone:compileFdroidDebugKotlin`.
> - A notification channel's sound/importance is fixed at creation and can't
>   be changed later without a new channel id — that's *why* the leave-now
>   nudge needed its own channel instead of reusing the silent tracking one.
> - `by remember { mutableStateOf(...) }` needs `androidx.compose.runtime.getValue`
>   / `.setValue` imported (not just `remember`/`mutableStateOf`) or the `by`
>   delegate fails to resolve — easy to miss when adding a first `var ... by`
>   to a file that only had `val ... by collectAsState()` before.

### 3.1 First-run experience ✅ DONE

Cold start immediately fires the location+notification permission dialogs onto
an empty screen. A single intro panel ("Shows departures near your saved
stops — needs location") before the system dialog improves grant rates, and
the empty Departures state should offer an "Add a stop" button directly rather
than routing through the Favorites tab.

### 3.2 Alerts are buried ✅ DONE

An alert is a 16 dp warning icon; the text is two taps away. For a known trip,
the alert is often the *only* new information ("N delayed 20 min"). Show the
alert headline as a single line on the affected departure card / favorite row;
tap to expand. Consider including the headline in the Live Update notification
when the tracked stop has an active alert.

### 3.3 Vehicle position during tracking (stretch) — NOT DONE, deferred

511 GTFS-RT includes vehicle positions. A countdown that jumps from 5 min to
9 min feels broken; "the bus is at 24th St" explains itself. Even a text line
("approaching · 3 stops away") in the Live Update notification — no map
required — would make tracking dramatically more trustworthy. A small static
map is optional and should stay subordinate to the notification surface.

**Deferred by explicit choice on 2026-07-17** — scoped out during batch 4/5 as
disproportionate to the other five items combined. What it actually requires,
for whoever picks this up:

- Server: a `download_vehiclepositions()` fetcher (`gtfs511/download.py`,
  mirrors `download_tripupdates()`), a new `rt_vehicle_positions` table parsed
  in `rt_db.build_rt_db` (trip_id, current_stop_sequence, current_status,
  vehicle timestamp), and wiring into `api.py`'s `_download_rt_data`/`_build_rt`
  refresh cycle.
- A lookup (`gtfs511/lookup.py`) that resolves a trip via
  `rt_trip_stop_times` (route + headsign → trip_id) and computes stops-away
  from `target_stop_sequence - current_stop_sequence`.
- **Don't thread this through the existing departures endpoint** — its
  19-field shape (`lookup._DEPARTURE_FIELDS`) is pinned by `test_proxy.py` and
  shared by both the local-511 and Transitland-parity paths; Transitland has
  no equivalent field to backfill. A small dedicated endpoint (e.g.
  `/api/v2/rest/vehicle_status?feed=&stop_id=&route_short_name=&headsign=`)
  keeps the departures contract untouched.
- Client: a `TransitlandClient` call, then plumb into `LiveUpdateService`
  (polled like the existing 60s departures refresh) and a new line in
  `LiveUpdateNotificationBuilder`.

### 3.4 Distance-unit control ✅ DONE

Settings uses a `Switch` labeled "Miles/Kilometers" — a switch implies on/off,
not a choice between two values. Use a `SegmentedButton` (M3) instead. Minor.

---

## Explicitly out of scope (routing-adjacent features to *not* build)

- **Trip planning / journey search** — the core product decision; other apps
  do this well and the habitual rider doesn't need it.
- **Full interactive map browsing** — a map is a discovery tool. The only map
  candidates worth considering are the minimal ones above (pin-picker if the
  metadata-rich stop list of 2.6 proves insufficient; static context on stop
  details), and neither should become a primary surface.
- **Multi-modal comparison, fares, bike/scooter integration** — same
  reasoning.

---

## Suggested sequencing

| Batch | Items | Rationale |
|-------|-------|-----------|
| 1 ✅ | 2.1, 2.2, 2.3, 1.5 | **DONE 2026-07-17.** Small, contained in `phone/` + shared UI; fixes everything that feels broken and un-hides tracking |
| 2 ✅ | 1.1, 2.6 | **DONE 2026-07-17.** Route filter + routes-served metadata share one backend change; highest product value |
| 3 ✅ | 1.2, 1.4 | **DONE 2026-07-17.** Widget rework and nickname/order, both riding on the filter/sync work from batch 2 |
| 4 ✅ | 1.3, 3.2, 2.4 | **DONE 2026-07-17.** Leave-by nudges and alert surfacing build on the tracking service |
| 5 ✅ | 3.1, 3.4, 2.5 | **DONE 2026-07-17.** Polish. (3.3 deferred — see its entry in Priority 3.) |
