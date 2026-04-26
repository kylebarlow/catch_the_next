package dev.catchthenext.android.tile

import dev.catchthenext.model.Departure
import dev.catchthenext.model.DepartureTimeSource
import dev.catchthenext.model.Stop
import dev.catchthenext.android.location.LatLon
import dev.catchthenext.android.location.closestTo
import dev.catchthenext.android.location.haversineMeters
import dev.catchthenext.android.location.withinMeters
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

internal const val CACHE_TTL_MS = 60_000L
internal const val MAX_BATCH_STOPS = 4

data class CachedDeparture(
    val routeShortName: String,
    val headsign: String,
    val departureEpochMillis: Long,
    val timeSource: DepartureTimeSource
) {
    fun currentMinutes(): Long = (departureEpochMillis - System.currentTimeMillis()) / 60_000
}

data class StopWithDepartures(
    val stop: Stop,
    val distanceMeters: Double,
    val departures: List<CachedDeparture>,
    val fetchedAt: Long = System.currentTimeMillis(),
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
suspend fun updateNearbyStopsDepartures(
    lat: Double,
    lon: Double,
    favorites: List<Stop>,
    thresholdMeters: Int,
    fetchDeparturesBatch: suspend (stopIds: List<Long>) -> Map<Long, Pair<List<CachedDeparture>, Long>>,
    persistDepartures: suspend (List<StopWithDepartures>) -> Unit = {},
    maxStops: Int = 4,
): TileState {
    val inRange = favorites.withinMeters(lat, lon, thresholdMeters).take(maxStops)
    val selected: List<Pair<Stop, Double>> = if (inRange.isNotEmpty()) {
        inRange
    } else {
        val closest = favorites.closestTo(lat, lon) ?: return TileState.NoFavorites
        listOf(Pair(closest, haversineMeters(lat, lon, closest.lat, closest.lon))
        )
    }

    val selectedIds = selected.map { it.first.id }
    val result = runCatching { fetchDeparturesBatch(selectedIds) }

    if (result.isFailure) {
        val errMsg = result.exceptionOrNull()?.message ?: "Could not load departures"
        return TileState.NetworkError(errMsg)
    }

    val batchMap = result.getOrThrow()
    val successful = mutableListOf<StopWithDepartures>()
    for ((stop, distanceMeters) in selected) {
        val (deps, fetchedAt) = batchMap[stop.id] ?: continue
        val filtered = deps.filter { it.currentMinutes() >= 0 }
        successful.add(StopWithDepartures(stop = stop, distanceMeters = distanceMeters, departures = filtered, fetchedAt = fetchedAt))
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
)

data class GroupedDepartureTime(
    val minutes: Long,
    val timeSource: DepartureTimeSource,
)

fun groupDepartures(
    stops: List<StopWithDepartures>,
    maxPerGroup: Int = 3,
    filter: (CachedDeparture) -> Boolean = { it.currentMinutes() >= 0 },
): List<GroupedDeparture> {
    val showStopTag = stops.size > 1
    return stops.sortedBy { it.distanceMeters }.flatMap { swd ->
        swd.departures
            .filter(filter)
            .groupBy { it.routeShortName to it.headsign }
            .map { (key, deps) ->
                GroupedDeparture(
                    routeShortName = key.first,
                    headsign = key.second,
                    stopName = swd.stop.stopName,
                    showStopTag = showStopTag,
                    times = deps.map { GroupedDepartureTime(it.currentMinutes(), it.timeSource) }.sortedBy { it.minutes }.take(maxPerGroup),
                )
            }
            .sortedBy { it.times.firstOrNull()?.minutes ?: Long.MAX_VALUE }
    }
}

suspend fun computeTileState(
    favorites: List<Stop>,
    location: LatLon?,
    hasPermission: Boolean,
    thresholdMeters: Int = 1609,
    fetchDeparturesBatch: suspend (stopIds: List<Long>) -> Map<Long, Pair<List<CachedDeparture>, Long>>,
    persistDepartures: suspend (List<StopWithDepartures>) -> Unit = {},
): TileState {
    if (!hasPermission) return TileState.NoPermission
    if (favorites.isEmpty()) return TileState.NoFavorites
    if (location == null) return TileState.NoLocation
    return updateNearbyStopsDepartures(
        location.lat, location.lon, favorites, thresholdMeters, fetchDeparturesBatch, persistDepartures
    )
}

/** Network fetch + per-stop 60 s cache check + error fallback for a single stop. */
fun makeFetchNetworkDepartures(
    getDepartures: suspend (Long) -> List<Departure>,
    cache: CachedTileData,
    forceFresh: Boolean = false,
): suspend (Long) -> Pair<List<CachedDeparture>, Long> = { stopId ->
    val now = System.currentTimeMillis()
    val cached = cache.nearbyDepartures.firstOrNull { it.stopId == stopId }
    if (!forceFresh && cached != null && now - cached.fetchedAt < CACHE_TTL_MS) {
        Pair(cached.departures, cached.fetchedAt)
    } else {
        runCatching {
            val deps = getDepartures(stopId)
            val fetchTime = System.currentTimeMillis()
            Pair(deps.map { dep ->
                CachedDeparture(dep.routeShortName, dep.headsign, fetchTime + dep.displayDepartureMinutes * 60_000, dep.timeSource)
            }, fetchTime)
        }.getOrElse { e ->
            if (cached != null && cached.departures.isNotEmpty()) {
                Pair(cached.departures, cached.fetchedAt)
            } else throw e
        }
    }
}

/** Batch-aware network fetch + per-stop 60 s cache check + error fallback. */
fun makeFetchNetworkDeparturesBatch(
    getDeparturesBatch: suspend (List<Long>) -> Map<Long, List<Departure>>,
    cache: CachedTileData,
    forceFresh: Boolean = false,
): suspend (List<Long>) -> Map<Long, Pair<List<CachedDeparture>, Long>> = { stopIds ->
    if (stopIds.isEmpty()) {
        emptyMap()
    } else {
        val now = System.currentTimeMillis()
        val result = mutableMapOf<Long, Pair<List<CachedDeparture>, Long>>()

        val staleStopIds = mutableListOf<Long>()
        for (stopId in stopIds) {
            val cached = cache.nearbyDepartures.firstOrNull { it.stopId == stopId }
            if (!forceFresh && cached != null && now - cached.fetchedAt < CACHE_TTL_MS) {
                result[stopId] = Pair(cached.departures, cached.fetchedAt)
            } else {
                staleStopIds.add(stopId)
            }
        }

        if (staleStopIds.isNotEmpty()) {
            runCatching {
                val batchResult = getDeparturesBatch(staleStopIds)
                val fetchTime = System.currentTimeMillis()
                for (stopId in staleStopIds) {
                    val deps = batchResult[stopId] ?: emptyList()
                    result[stopId] = Pair(deps.map { dep ->
                        CachedDeparture(dep.routeShortName, dep.headsign, fetchTime + dep.displayDepartureMinutes * 60_000, dep.timeSource)
                    }, fetchTime)
                }
            }.getOrElse { e ->
                for (stopId in staleStopIds) {
                    if (stopId !in result) {
                        val cached = cache.nearbyDepartures.firstOrNull { it.stopId == stopId }
                        if (cached != null && cached.departures.isNotEmpty()) {
                            result[stopId] = Pair(cached.departures, cached.fetchedAt)
                        }
                    }
                }
                if (result.isEmpty()) throw e
            }
        }

        result.toMap()
    }
}
