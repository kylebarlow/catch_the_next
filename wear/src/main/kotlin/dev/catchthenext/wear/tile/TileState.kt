package dev.catchthenext.wear.tile

import dev.catchthenext.api.TransitlandClient
import dev.catchthenext.model.Stop
import dev.catchthenext.wear.location.LatLon
import dev.catchthenext.wear.location.closestTo
import dev.catchthenext.wear.location.haversineMeters
import dev.catchthenext.wear.location.withinMeters
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

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
    val departures: List<CachedDeparture>
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

    val fetchedAt = System.currentTimeMillis()
    // Pair<StopWithDepartures?, Throwable?> to preserve error messages on total failure
    val allResults: List<Pair<StopWithDepartures?, Throwable?>> = coroutineScope {
        selected.map { (stop, distanceMeters) ->
            async {
                runCatching { fetchDepartures(stop.id) }
                    .fold(
                        onSuccess = { (deps, _) ->
                            Pair(
                                StopWithDepartures(
                                    stop = stop,
                                    distanceMeters = distanceMeters,
                                    departures = deps.filter { it.currentMinutes() >= 0 }
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
        TileState.Ready(successful, fetchedAt)
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

/** Network fetch + cache fallback for a single stop. Does not persist (caller persists the full set). */
fun makeFetchNetworkDepartures(
    client: TransitlandClient,
    cache: CachedTileData,
): suspend (Long) -> Pair<List<CachedDeparture>, Long> = { stopId ->
    runCatching {
        val deps = client.getDepartures(stopId)
        val now = System.currentTimeMillis()
        Pair(deps.map { dep ->
            CachedDeparture(dep.routeShortName, dep.headsign, now + dep.departureMinutes * 60_000)
        }, now)
    }.getOrElse { e ->
        val cached = cache.nearbyDepartures.firstOrNull { it.stopId == stopId }
        if (cached != null && cached.departures.isNotEmpty()) {
            Pair(cached.departures, cached.fetchedAt)
        } else throw e
    }
}
