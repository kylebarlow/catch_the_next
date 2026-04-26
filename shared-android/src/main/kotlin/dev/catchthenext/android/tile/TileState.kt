package dev.catchthenext.android.tile

import dev.catchthenext.model.Departure
import dev.catchthenext.model.Stop
import dev.catchthenext.android.location.LatLon
import dev.catchthenext.android.location.closestTo
import dev.catchthenext.android.location.haversineMeters
import dev.catchthenext.android.location.withinMeters
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

internal const val CACHE_TTL_MS = 60_000L

data class CachedDeparture(
    val routeShortName: String,
    val headsign: String,
    val scheduledEpochMillis: Long
) {
    fun currentMinutes(): Long = (scheduledEpochMillis - System.currentTimeMillis()) / 60_000
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
    fetchDepartures: suspend (stopId: Long) -> Pair<List<CachedDeparture>, Long>,
    persistDepartures: suspend (List<StopWithDepartures>) -> Unit = {},
    maxStops: Int = 4,
): TileState {
    val inRange = favorites.withinMeters(lat, lon, thresholdMeters).take(maxStops)
    val selected: List<Pair<Stop, Double>> = if (inRange.isNotEmpty()) {
        inRange
    } else {
        val closest = favorites.closestTo(lat, lon) ?: return TileState.NoFavorites
        listOf(Pair(closest, haversineMeters(lat, lon, closest.lat, closest.lon)))
    }

    // Pair<StopWithDepartures?, Throwable?> to preserve error messages on total failure
    val allResults: List<Pair<StopWithDepartures?, Throwable?>> = coroutineScope {
        selected.map { (stop, distanceMeters) ->
            async {
                runCatching { fetchDepartures(stop.id) }
                    .fold(
                        onSuccess = { (deps, stopFetchedAt) ->
                            Pair(
                                StopWithDepartures(
                                    stop = stop,
                                    distanceMeters = distanceMeters,
                                    departures = deps.filter { it.currentMinutes() >= 0 },
                                    fetchedAt = stopFetchedAt,
                                ),
                                null
                            )
                        },
                        onFailure = { e -> Pair(null, e) }
                    )
            }
        }.awaitAll()
    }

    val successful = allResults.mapNotNull { it.first }
    return if (successful.isEmpty()) {
        val errMsg = allResults.firstNotNullOfOrNull { it.second?.message } ?: "Could not load departures"
        TileState.NetworkError(errMsg)
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
    val minutesList: List<Long>,
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
                    minutesList = deps.map { it.currentMinutes() }.sorted().take(maxPerGroup),
                )
            }
            .sortedBy { it.minutesList.firstOrNull() ?: Long.MAX_VALUE }
    }
}

suspend fun computeTileState(
    favorites: List<Stop>,
    location: LatLon?,
    hasPermission: Boolean,
    thresholdMeters: Int = 1609,
    fetchDepartures: suspend (stopId: Long) -> Pair<List<CachedDeparture>, Long>,
    persistDepartures: suspend (List<StopWithDepartures>) -> Unit = {},
): TileState {
    if (!hasPermission) return TileState.NoPermission
    if (favorites.isEmpty()) return TileState.NoFavorites
    if (location == null) return TileState.NoLocation
    return updateNearbyStopsDepartures(
        location.lat, location.lon, favorites, thresholdMeters, fetchDepartures, persistDepartures
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
                CachedDeparture(dep.routeShortName, dep.headsign, fetchTime + dep.departureMinutes * 60_000)
            }, fetchTime)
        }.getOrElse { e ->
            if (cached != null && cached.departures.isNotEmpty()) {
                Pair(cached.departures, cached.fetchedAt)
            } else throw e
        }
    }
}
