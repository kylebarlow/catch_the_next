# Wear OS Implementation Plan for Catch The Next

This document outlines the implementation plan for the Wear OS UI (App and Tile) for the "Catch The Next" transit tracker. The foundational restructuring is complete, and we are moving into the active UI and Tile development phase.

## 1. Completed Setup
- [x] **Project Restructuring**: Split project into `core`, `cli`, and `wear` modules.
- [x] **Interface Refactoring**: `FavoritesManager` is an interface in `core`.
- [x] **Wear Module Scaffolding**: Android build configuration, dependencies, and manifest created.
- [x] **SDK/Build Configuration**: `local.properties` and `gradle.properties` configured for headless builds; API key injection configured.

## 2. Next Steps: UI & Tile Implementation

### Implementation of `AndroidFavoritesManager`
- [ ] Implement `AndroidFavoritesManager` in the `wear` module using `androidx.datastore` to persist favorite stops.

### UI Implementation: Settings App (Compose)
The app acts as the "setting selector" for configuring favorite stops.

- **Architecture**: Use ViewModels for state management, leveraging Kotlin Coroutines to call the `TransitlandClient` on `Dispatchers.IO`.
- **Navigation Flow**:
  - **Screen A: Favorites List**: `ScalingLazyColumn` (from Horologist) displaying saved favorites.
  - **Screen B: Add Favorite**: Location-based search (`FusedLocationProviderClient`) + `ScalingLazyColumn` for results.
  - **Screen C: Stop Details**: Departure preview list and "Add/Remove Favorite" action button.

### Tile Implementation (`ClosestStopTileService`)
The Tile provides the glanceable, zero-click experience.

- **Architecture**: Extend `CoroutinesTileService` (Horologist).
- **Workflow**:
  1. Permission check (Location).
  2. Fetch favorites from DataStore.
  3. Fetch current location.
  4. Calculate closest favorite stop.
  5. Fetch departures for closest stop.
  6. Render `PrimaryLayout`.

## 3. API/Data Handling Considerations
- **BuildConfig**: `APP_API_KEY` is already injected via `BuildConfig` in `wear/build.gradle.kts`.
- **Error Handling**: Wrap `TransitlandClient` network calls in `runCatching` or `try/catch` and expose UI states (`Loading`, `Success`, `Error`) to gracefully handle network issues.
