package dev.catchthenext.android.tile

/** The tile only shows the next hour; matches the wear app screen's departures list. */
const val TILE_DEPARTURE_HORIZON_MS = 60 * 60_000L

/** Departure filter shared by the tile's layout and its timeline boundaries. */
val tileDepartureFilter: (CachedDeparture) -> Boolean = { it.currentMinutes() in 0..59 }

/**
 * Slices a [TileState.Ready] into the successive views the tile should show as time passes, so a
 * departure drops off the tile at its own time instead of waiting for the next `tileRequest`.
 * Pure — the wear module turns each slice into a `TimelineEntry` with a validity interval.
 */
data class TimelineSlice(
    /** Departures still worth showing at this slice; render from these. */
    val stops: List<StopWithDepartures>,
    /** Wall-clock millis when this slice stops being valid; null for the terminal slice. */
    val validUntilMillis: Long?,
)

/**
 * One slice per distinct *displayed* departure instant, in ascending order, each valid until that
 * departure plus [nowGraceMs] (a departure keeps reading "Now" for the grace period, matching the
 * `currentMinutes() >= 0` filters elsewhere); plus a terminal slice holding whatever remains after
 * the last boundary, with no expiry. Capped at [maxSlices] entries in total.
 *
 * Only the departures the tile actually renders create boundaries: anything past [maxGroups] /
 * [maxPerGroup] is invisible, so its passing changes nothing on screen.
 */
fun buildTimelineSlices(
    state: TileState.Ready,
    now: Long,
    maxGroups: Int = Tuning.TILE_MAX_GROUPS,
    maxPerGroup: Int = 3,
    maxSlices: Int = Tuning.TILE_MAX_TIMELINE_SLICES,
    nowGraceMs: Long = Tuning.TILE_NOW_GRACE_MS,
    horizonMs: Long = TILE_DEPARTURE_HORIZON_MS,
): List<TimelineSlice> {
    val boundaries = groupDepartures(state.stops, maxPerGroup = maxPerGroup, filter = tileDepartureFilter)
        .take(maxGroups)
        .flatMap { group -> group.times.map { it.departureEpochMillis } }
        .filter { it >= now && it - now < horizonMs }
        .distinct()
        .sorted()
        .take((maxSlices - 1).coerceAtLeast(0))

    val slices = boundaries.map { boundary ->
        TimelineSlice(
            stops = state.stops.filterDeparturesFrom(boundary),
            validUntilMillis = boundary + nowGraceMs,
        )
    }
    val lastBoundary = boundaries.lastOrNull()
    val terminal = TimelineSlice(
        stops = if (lastBoundary == null) state.stops
        else state.stops.filterDeparturesAfter(lastBoundary),
        validUntilMillis = null,
    )
    return slices + terminal
}

/** Keeps departures at or after [from]; every other field of each stop is untouched. */
private fun List<StopWithDepartures>.filterDeparturesFrom(from: Long): List<StopWithDepartures> =
    map { swd -> swd.copy(departures = swd.departures.filter { it.departureEpochMillis >= from }) }

private fun List<StopWithDepartures>.filterDeparturesAfter(after: Long): List<StopWithDepartures> =
    map { swd -> swd.copy(departures = swd.departures.filter { it.departureEpochMillis > after }) }
