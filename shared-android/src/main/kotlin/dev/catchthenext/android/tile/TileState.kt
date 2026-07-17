package dev.catchthenext.android.tile

import dev.catchthenext.api.StopDepartures
import dev.catchthenext.model.Alert
import dev.catchthenext.model.Departure
import dev.catchthenext.model.DepartureTimeSource
import dev.catchthenext.model.Stop
import dev.catchthenext.android.location.LatLon
import dev.catchthenext.android.util.toNetworkMessage
import dev.catchthenext.android.location.closestTo
import dev.catchthenext.android.location.haversineMeters
import dev.catchthenext.android.location.withinMeters
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

data class CachedDeparture(
    val routeShortName: String,
    val headsign: String,
    val departureEpochMillis: Long,
    val timeSource: DepartureTimeSource,
    val agencyName: String? = null,
) {
    fun currentMinutes(): Long = (departureEpochMillis - System.currentTimeMillis()) / 60_000
}

data class StopWithDepartures(
    val stop: Stop,
    val distanceMeters: Double,
    val departures: List<CachedDeparture>,
    val fetchedAt: Long = System.currentTimeMillis(),
    val alerts: List<Alert> = emptyList(),
    val isStale: Boolean = false,
)

sealed interface TileState {
    object NoFavorites : TileState
    object NoPermission : TileState
    object NoLocation : TileState
    data class NetworkError(val message: String) : TileState
    data class Ready(
        val stops: List<StopWithDepartures>,
        val fetchedAt: Long
    ) : TileState
}

/**
 * Core tile logic — testable without Android dependencies.
 *
 * Fetches departures for all favorites within [thresholdMeters]. Falls back to the single
 * closest favorite when none are in range. [fetchDepartures] handles network + cache fallback
 * per stop. [persistDepartures] is called once with all successful results.
 */
data class CachedStopFetch(
    val departures: List<CachedDeparture>,
    val alerts: List<Alert>,
    val fetchedAt: Long,
    val isStale: Boolean = false,
)

suspend fun updateNearbyStopsDepartures(
    lat: Double,
    lon: Double,
    favorites: List<Stop>,
    thresholdMeters: Int,
    fetchDeparturesBatch: suspend (stopIds: List<Long>) -> Map<Long, CachedStopFetch>,
    persistDepartures: suspend (List<StopWithDepartures>) -> Unit = {},
    maxStops: Int = Tuning.MAX_BATCH_STOPS,
): TileState {
    val inRange = favorites.withinMeters(lat, lon, thresholdMeters).take(maxStops)
    val selected: List<Pair<Stop, Double>> = if (inRange.isNotEmpty()) {
        inRange
    } else {
        val closest = favorites.closestTo(lat, lon) ?: return TileState.NoFavorites
        listOf(Pair(closest, haversineMeters(lat, lon, closest.lat, closest.lon)))
    }

    val selectedIds = selected.map { it.first.id }
    val result = runCatching { fetchDeparturesBatch(selectedIds) }

    if (result.isFailure) {
        val errMsg = result.exceptionOrNull()?.toNetworkMessage() ?: "Could not load departures"
        return TileState.NetworkError(errMsg)
    }

    val batchMap = result.getOrThrow()
    val successful = mutableListOf<StopWithDepartures>()
    for ((stop, distanceMeters) in selected) {
        val fetch = batchMap[stop.id] ?: continue
        val filtered = fetch.departures.filter { it.currentMinutes() >= 0 }
        successful.add(StopWithDepartures(stop = stop, distanceMeters = distanceMeters, departures = filtered, alerts = fetch.alerts, fetchedAt = fetch.fetchedAt, isStale = fetch.isStale))
    }

    return if (successful.isEmpty()) {
        TileState.NetworkError("No departures available")
    } else {
        persistDepartures(successful)
        TileState.Ready(successful, successful.minOf { it.fetchedAt })
    }
}

data class GroupedDeparture(
    val routeShortName: String,
    val headsign: String,
    val stopName: String,
    val showStopTag: Boolean,
    val times: List<GroupedDepartureTime>,
    val agencyName: String? = null,
    val hasAlert: Boolean = false,
    val stop: Stop,
)

data class GroupedDepartureTime(
    val minutes: Long,
    val timeSource: DepartureTimeSource,
    /** Absolute clock label ("3:42 PM") for far-out departures; null for near-term ones. */
    val clockTime: String? = null,
)

/** Departures at least this many minutes out also show an absolute clock time. */
private const val ABSOLUTE_TIME_MIN_MINUTES = 15L

fun groupDepartures(
    stops: List<StopWithDepartures>,
    maxPerGroup: Int = 3,
    filter: (CachedDeparture) -> Boolean = { it.currentMinutes() >= 0 },
): List<GroupedDeparture> {
    val showStopTag = stops.size > 1
    return stops.sortedBy { it.distanceMeters }.flatMap { swd ->
        swd.departures
            .filter { filter(it) && swd.stop.showsRoute(it.routeShortName) }
            .groupBy { it.routeShortName to it.headsign }
            .map { (key, deps) ->
                GroupedDeparture(
                    routeShortName = key.first,
                    headsign = key.second,
                    stopName = swd.stop.displayName,
                    showStopTag = showStopTag,
                    times = deps.map {
                        val mins = it.currentMinutes()
                        GroupedDepartureTime(
                            minutes = mins,
                            timeSource = it.timeSource,
                            clockTime = if (mins >= ABSOLUTE_TIME_MIN_MINUTES) clockTimeLabel(it.departureEpochMillis) else null,
                        )
                    }.sortedBy { it.minutes }.take(maxPerGroup),
                    agencyName = deps.firstOrNull()?.agencyName,
                    hasAlert = swd.alerts.isNotEmpty(),
                    stop = swd.stop,
                )
            }
            .sortedBy { it.times.firstOrNull()?.minutes ?: Long.MAX_VALUE }
    }
}

suspend fun computeTileState(
    favorites: List<Stop>,
    location: LatLon?,
    hasPermission: Boolean,
    thresholdMeters: Int = Tuning.DEFAULT_THRESHOLD_METERS,
    fetchDeparturesBatch: suspend (stopIds: List<Long>) -> Map<Long, CachedStopFetch>,
    persistDepartures: suspend (List<StopWithDepartures>) -> Unit = {},
): TileState {
    if (!hasPermission) return TileState.NoPermission
    if (favorites.isEmpty()) return TileState.NoFavorites
    if (location == null) return TileState.NoLocation
    return updateNearbyStopsDepartures(
        location.lat, location.lon, favorites, thresholdMeters, fetchDeparturesBatch, persistDepartures
    )
}

/**
 * For any stops in [readyState] flagged as stale (their Transitland integer ID has been rotated),
 * searches nearby using the stored lat/lon and matches by GTFS stop_id to find the new integer ID.
 * Returns the updated favorites list if any IDs were resolved, null if nothing changed.
 */
suspend fun resolveStaleStops(
    readyState: TileState.Ready,
    allFavorites: List<Stop>,
    getNearbyStops: suspend (lat: Double, lon: Double) -> List<Stop>,
): List<Stop>? {
    val staleStops = readyState.stops.filter { it.isStale }.map { it.stop }
    if (staleStops.isEmpty()) return null

    val updated = allFavorites.toMutableList()
    var anyResolved = false
    for (staleStop in staleStops) {
        val nearby = getNearbyStops(staleStop.lat, staleStop.lon)
        val match = nearby.firstOrNull { it.stopId == staleStop.stopId }
            ?: nearby.firstOrNull { it.onestopId != null && it.onestopId == staleStop.onestopId }
        if (match != null && match.id != staleStop.id) {
            val idx = updated.indexOfFirst { it.id == staleStop.id }
            if (idx >= 0) {
                // Carry the user's per-favorite settings onto the re-resolved stop.
                updated[idx] = match.copy(
                    nickname = staleStop.nickname,
                    shownRoutes = staleStop.shownRoutes,
                    sortOrder = staleStop.sortOrder,
                )
                anyResolved = true
            }
        }
    }
    return if (anyResolved) updated else null
}

private fun CachedTileData.cachedFor(stopId: Long): CachedStopDepartures? =
    nearbyDepartures.firstOrNull { it.stopId == stopId }

private fun CachedStopDepartures.toFetch(): CachedStopFetch =
    CachedStopFetch(departures, alerts ?: emptyList(), fetchedAt)

private fun StopDepartures.toFetch(fetchTime: Long): CachedStopFetch = CachedStopFetch(
    departures = departures.map { dep ->
        CachedDeparture(dep.routeShortName, dep.headsign, fetchTime + dep.displayDepartureMinutes * 60_000, dep.timeSource, dep.agencyName)
    },
    alerts = alerts,
    fetchedAt = fetchTime,
)

/** Batch-aware network fetch + per-stop 60 s cache check + error fallback. */
fun makeFetchNetworkDeparturesBatch(
    getDeparturesBatch: suspend (List<String>) -> Map<String, StopDepartures>,
    cache: CachedTileData,
    stops: List<Stop>,
    forceFresh: Boolean = false,
): suspend (List<Long>) -> Map<Long, CachedStopFetch> = { stopIds ->
    if (stopIds.isEmpty()) {
        emptyMap()
    } else {
        val now = System.currentTimeMillis()
        val result = mutableMapOf<Long, CachedStopFetch>()

        val staleStopIds = mutableListOf<Long>()
        for (stopId in stopIds) {
            val cached = cache.cachedFor(stopId)
            if (!forceFresh && cached != null && now - cached.fetchedAt < Tuning.CACHE_TTL_MS) {
                result[stopId] = cached.toFetch()
            } else {
                staleStopIds.add(stopId)
            }
        }

        if (staleStopIds.isNotEmpty()) {
            val idToStop = stops.associateBy { it.id }
            // Map onestop ID → internal Long stop ID for reverse lookup after the network call.
            val onestopToLong = staleStopIds.mapNotNull { stopId ->
                val onestopId = idToStop[stopId]?.onestopId ?: return@mapNotNull null
                onestopId to stopId
            }.toMap()

            // Stops without an onestop ID can't be network-fetched; use stale cache immediately.
            val queryableIds = onestopToLong.values.toSet()
            for (stopId in staleStopIds) {
                if (stopId !in queryableIds) {
                    val cached = cache.cachedFor(stopId)
                    if (cached != null && cached.departures.isNotEmpty()) {
                        result[stopId] = cached.toFetch()
                    }
                }
            }

            if (onestopToLong.isNotEmpty()) {
                runCatching {
                    val batchResult = getDeparturesBatch(onestopToLong.keys.toList())
                    val fetchTime = System.currentTimeMillis()
                    for ((onestopId, stopId) in onestopToLong) {
                        val stopDeps = batchResult[onestopId] ?: continue
                        result[stopId] = stopDeps.toFetch(fetchTime)
                    }
                }.getOrElse { e ->
                    for (stopId in onestopToLong.values) {
                        if (stopId !in result) {
                            val cached = cache.cachedFor(stopId)
                            if (cached != null && cached.departures.isNotEmpty()) {
                                result[stopId] = cached.toFetch()
                            }
                        }
                    }
                    if (result.isEmpty()) throw e
                }
            }
        }

        result.toMap()
    }
}
