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

### 1.1 Route/direction filtering per favorite

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

### 1.2 Widgets that show departure times

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

### 1.3 "Leave now" nudges

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

### 1.4 Nicknames and manual ordering for favorites

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

### 2.4 Configurable departure window

`ReadyContent` hard-codes `currentMinutes() in 0..59`. Habitual riders of
infrequent lines (ferries, commuter buses that run every 40–60 min) will see
an empty screen. Make the window a setting or simply extend to 90–120 min with
absolute times (2.3) so the longer horizon stays readable.

### 2.5 Deep-link handler drops non-numeric ids

`MainActivity` (`PhoneNavGraph` LaunchedEffect) only navigates when the deep
link's last segment parses as `Long`; a onestop-style string id is consumed
and silently discarded. Either resolve onestop ids to the internal stop id or
stop emitting such links.

### 2.6 Stop pickers show only names

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

### 3.1 First-run experience

Cold start immediately fires the location+notification permission dialogs onto
an empty screen. A single intro panel ("Shows departures near your saved
stops — needs location") before the system dialog improves grant rates, and
the empty Departures state should offer an "Add a stop" button directly rather
than routing through the Favorites tab.

### 3.2 Alerts are buried

An alert is a 16 dp warning icon; the text is two taps away. For a known trip,
the alert is often the *only* new information ("N delayed 20 min"). Show the
alert headline as a single line on the affected departure card / favorite row;
tap to expand. Consider including the headline in the Live Update notification
when the tracked stop has an active alert.

### 3.3 Vehicle position during tracking (stretch)

511 GTFS-RT includes vehicle positions. A countdown that jumps from 5 min to
9 min feels broken; "the bus is at 24th St" explains itself. Even a text line
("approaching · 3 stops away") in the Live Update notification — no map
required — would make tracking dramatically more trustworthy. A small static
map is optional and should stay subordinate to the notification surface.

### 3.4 Distance-unit control

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
| 2 | 1.1, 2.6 | Route filter + routes-served metadata share one backend change; highest product value |
| 3 | 1.2, 1.4 | Widget rework and nickname/order, both riding on the filter/sync work from batch 2 |
| 4 | 1.3, 3.2, 2.4 | Leave-by nudges and alert surfacing build on the tracking service |
| 5 | 3.1, 3.3, 3.4, 2.5 | Polish |
