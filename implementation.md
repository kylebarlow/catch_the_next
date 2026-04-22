# Wear OS Implementation Plan for Catch The Next

This document outlines the detailed plan for implementing the Wear OS UI (App and Tile) for the "Catch The Next" transit tracker. The plan focuses on keeping the implementation simple, intuitive, and highly dependent on modern Wear OS UI libraries (Wear Compose and Horologist).

## 1. Project Restructuring

Currently, the `app` module is a standalone Kotlin JVM project meant for the CLI. To introduce a Wear OS application while sharing the existing logic:
- Rename `app` to `core` (or `shared`). Update `settings.gradle.kts` accordingly.
- Keep `core` as a pure Kotlin module (`org.jetbrains.kotlin.jvm`), housing `TransitlandClient`, `Stop`, and `Departure`.
- Convert `FavoritesManager` into an interface in `core`, with a `CliFavoritesManager` implementation for the JVM app, and an `AndroidFavoritesManager` (backed by Android DataStore) to be implemented in the new `wear` module.
- Create a new Android application module named `wear` in `settings.gradle.kts`.

## 2. Wear Module Setup (`wear/`)

Add the standard Android Wear OS configurations:
- **Build Plugin**: `com.android.application`, `org.jetbrains.kotlin.android`.
- **Min SDK**: 30 (Wear OS 3+).
- **Core Dependencies**:
  - `androidx.core:core-ktx`
  - `androidx.lifecycle:lifecycle-runtime-ktx`
  - `androidx.activity:activity-compose`
- **UI Frameworks (Compose & Horologist)**:
  - `androidx.wear.compose:compose-material`, `androidx.wear.compose:compose-foundation`, `androidx.wear.compose:compose-navigation`
  - `com.google.android.horologist:horologist-compose-layout`, `com.google.android.horologist:horologist-compose-material`
- **Location & Permissions**:
  - `com.google.android.gms:play-services-location`
  - Accompanist permissions or standard Compose permission wrappers.
- **Tile Dependencies**:
  - `androidx.wear.tiles:tiles`, `androidx.wear.tiles:tiles-material`
  - `androidx.wear.protolayout:protolayout`, `androidx.wear.protolayout:protolayout-material`
  - `com.google.android.horologist:horologist-tiles`
- **Storage**:
  - `androidx.datastore:datastore-preferences` for implementing `FavoritesManager`.

## 3. UI Implementation: Settings App (Compose)

The app itself acts as the "setting selector" for configuring favorite stops.

### Architecture
Use simple ViewModels for state management, leveraging Kotlin Coroutines to call the blocking `TransitlandClient` methods on `Dispatchers.IO`.

### Navigation Flow (`SwipeDismissableNavHost`)

**Screen A: Favorites List (Home)**
- A `ScalingLazyColumn` (from Horologist) displaying currently saved favorites.
- Each item is a Horologist `Chip` displaying the stop name.
- Tapping a stop navigates to **Screen C** (Stop Details).
- A primary `Button` or `Chip` at the bottom of the list: "Add Favorite", which navigates to **Screen B**.

**Screen B: Add Favorite (Location & Search)**
1. On load, check for `ACCESS_FINE_LOCATION`. If not granted, display a permission request UI (Horologist provides great permission components).
2. Once granted, use `FusedLocationProviderClient.getCurrentLocation` to get the GPS coordinates.
3. Show a loading indicator (`CircularProgressIndicator`).
4. Call `TransitlandClient.getNearbyStops(lat, lon)` (limit to ~5-10 to save API calls).
5. Render the results in a `ScalingLazyColumn`.
6. Tapping a nearby stop navigates to **Screen C**.

**Screen C: Stop Preview & Details**
- Displays the `Stop` name at the top.
- Calls `TransitlandClient.getDepartures` to load "sample upcoming departures".
- Renders the departures in a list so the user can verify they've selected the correct direction/station.
- **Action Button**:
  - If the stop is *not* a favorite: Show an "Add to Favorites" button. Tapping it saves to DataStore and pops the backstack.
  - If the stop *is* a favorite: Show a "Remove Favorite" (Delete) button. Tapping it removes the stop from DataStore and pops the backstack.

## 4. Tile Implementation (`ClosestStopTileService`)

The Tile provides the glanceable, zero-click experience.

### Architecture
- Extend `CoroutinesTileService` (from Horologist) to easily handle suspend functions for API calls and location fetching.
- Add the Tile to `AndroidManifest.xml` with the necessary intent filters and permissions (`ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`).

### Tile Logic Workflow (`tileRequest`)
1. **Permission Check**: Does the app have location permission?
   - *No*: Return a layout with text "Tap to setup" and a `Clickable` action that launches `MainActivity`.
2. **Fetch Favorites**: Read the list of saved stops from DataStore.
   - *Empty*: Return a layout "No favorites. Tap to add" opening `MainActivity`.
3. **Get GPS Location**: Fetch the current device location (or last known location to save time/battery).
4. **Calculate Closest**:
   - Iterate over favorites and compute distance (`Location.distanceBetween`).
   - Select the favorite stop with the minimum distance.
5. **Fetch Departures**:
   - Call `TransitlandClient.getDepartures(closestStop.id)`.
6. **Render Layout**:
   - Use `PrimaryLayout` (Horologist/ProtoLayout Material).
   - *Title*: `closestStop.stopName`
   - *Body*: A `Column` of `Text` elements displaying the top 2-3 upcoming departures (e.g., "Route 4 - 5 min", "Route 4 - 20 min").
   - *Action/Clickable*: Tapping the tile could either refresh the data (using `ActionBuilders.LoadAction`) or open the app.

## 5. Summary of API/Data Handling
- The CLI hardcodes the `.env` read for API keys. For the Wear module, configure `APP_API_KEY` via `BuildConfig` (in `build.gradle.kts`) so the `TransitlandClient` can be instantiated easily in Android.
- Wrap `TransitlandClient` network calls in `runCatching` or `try/catch` and expose UI states (`Loading`, `Success`, `Error`) to both the Compose UI and the Tile to gracefully handle "No Internet" or proxy timeouts.
