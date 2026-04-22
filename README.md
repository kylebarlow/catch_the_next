# Catch The Next

Backend logic and CLI test harness for a WearOS tile that shows the next transit departures from saved favorite stops, powered by the [Transitland v2 REST API](https://www.transit.land/documentation/rest-api).

## Quick start

```bash
cp .env.example .env
# add your Transitland API key to .env
./gradlew run --console=plain
```

The CLI walks through the full flow interactively:

1. Enter a lat/lon and search radius to find nearby stops
2. Select stops by number to save as favorites
3. Print next departures for all favorites — shows minutes until departure, route short name, and headsign

Stop IDs printed during the session can be hardcoded into future automated tests.

## Design decisions

**UI-free core.** `TransitlandClient`, `FavoritesManager`, `Stop`, and `Departure` have no Android or CLI dependencies. They will be imported directly into the WearOS tile module without modification.

**Departure timing.** The API returns a `departure.scheduled_utc` timestamp when available; minutes-from-now is computed from that. If the field is absent, it falls back to parsing the GTFS `HH:MM:SS` `departure_time` field against the current local time. GTFS times can exceed `24:00:00` for trips that cross midnight, so the parser handles that case.

**Favorites persistence.** The CLI saves favorites to `~/.catch_the_next/favorites.json` as a plain JSON array of `Stop` objects. The WearOS app will replace this with DataStore or SharedPreferences — the `FavoritesManager` interface is the seam for that swap.

**API key loading.** `TRANSITLAND_API_KEY` is read from the environment first, then from a `.env` file via dotenv-kotlin. The Android app will use BuildConfig or a secrets Gradle plugin instead; the env-loading code lives only in `cli/Main.kt`.

**Lookahead window.** Departures are fetched with `next=7200` (2 hours) and `relative_date=TODAY`. The WearOS tile will likely narrow this to 60–90 minutes and refresh on a schedule.

## Project layout

```
src/main/kotlin/dev/catchthenext/
├── api/
│   └── TransitlandClient.kt   # getNearbyStops(), getDepartures()
├── model/
│   ├── Stop.kt                # id, stopId (GTFS), stopName, lat, lon
│   └── Departure.kt           # departureMinutes, route info, displayString()
├── storage/
│   └── FavoritesManager.kt    # add / remove / list favorites
└── cli/
    └── Main.kt                # interactive test harness (not for production)
```

## Running the CLI test

Requires JDK 21. Gradle and Kotlin are managed by the wrapper.

```bash
./gradlew run --console=plain
```

To build a standalone fat-jar:

```bash
./gradlew jar
java -jar build/libs/catch-the-next-1.0-SNAPSHOT.jar
```

## Next steps

- [ ] Automated tests: hardcode stop IDs from CLI sessions and assert departure shapes against the live API
- [ ] WearOS tile module: import core packages, wire up `TileService`, render `Stop` + `Departure` data
- [ ] Favorite stop configuration activity: use the same `getNearbyStops` flow with a map/list UI
- [ ] Realtime data: Transitland returns a `schedule_relationship` field (`SCHEDULED`, `CANCELED`, `ADDED`); surface `CANCELED` trips in the tile
- [ ] Refresh strategy: decide tile refresh interval and whether to use WorkManager or the tile's built-in `onTileRequest` freshness window
- [ ] Offline handling: cache the last-known departures and display staleness indicator when the fetch fails
