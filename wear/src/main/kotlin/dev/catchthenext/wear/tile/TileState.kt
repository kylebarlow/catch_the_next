package dev.catchthenext.wear.tile

import dev.catchthenext.model.Stop
import dev.catchthenext.wear.location.LatLon
import dev.catchthenext.wear.location.closestTo

data class CachedDeparture(
    val routeShortName: String,
    val headsign: String,
    val scheduledEpochMillis: Long
) {
    fun currentMinutes(): Long = (scheduledEpochMillis - System.currentTimeMillis()) / 60_000
}

sealed interface TileState {
    object NoFavorites : TileState
    object NoPermission : TileState
    object NoLocation : TileState
    data class NetworkError(val message: String) : TileState
    data class Ready(
        val stop: Stop,
        val departures: List<CachedDeparture>,
        val fetchedAt: Long
    ) : TileState
}

/**
 * Core tile logic — testable without Android dependencies.
 *
 * [fetchDepartures] is a lambda taking a stop ID and returning (departures, fetchedAtMs).
 * It is responsible for network fetch, cache fallback, and persistence.
 * Throw to signal a hard failure with no usable data.
 */
suspend fun updateClosestStopDepartures(
    lat: Double,
    lon: Double,
    favorites: List<Stop>,
    fetchDepartures: suspend (stopId: Long) -> Pair<List<CachedDeparture>, Long>,
): TileState {
    val closest = favorites.closestTo(lat, lon) ?: return TileState.NoFavorites
    return runCatching { fetchDepartures(closest.id) }
        .map { (deps, fetchedAt) ->
            TileState.Ready(closest, deps.filter { it.currentMinutes() >= 0 }, fetchedAt)
        }
        .getOrElse { TileState.NetworkError(it.message ?: "Network error") }
}

suspend fun computeTileState(
    favorites: List<Stop>,
    location: LatLon?,
    hasPermission: Boolean,
    fetchDepartures: suspend (stopId: Long) -> Pair<List<CachedDeparture>, Long>,
): TileState {
    if (!hasPermission) return TileState.NoPermission
    if (favorites.isEmpty()) return TileState.NoFavorites
    if (location == null) return TileState.NoLocation
    return updateClosestStopDepartures(location.lat, location.lon, favorites, fetchDepartures)
}

/** Constructs the production fetchDepartures lambda: fetches from network, falls back to cache. */
fun makeFetchDepartures(
    favorites: List<Stop>,
    dataStore: TileDataStore,
    client: dev.catchthenext.api.TransitlandClient,
    cache: CachedTileData,
): suspend (Long) -> Pair<List<CachedDeparture>, Long> = { stopId ->
    runCatching {
        val deps = client.getDepartures(stopId)
        val now = System.currentTimeMillis()
        val cached = deps.map { dep ->
            CachedDeparture(dep.routeShortName, dep.headsign, now + dep.departureMinutes * 60_000)
        }
        val stop = favorites.first { it.id == stopId }
        dataStore.updateDepartures(stop, cached)
        Pair(cached, now)
    }.getOrElse { e ->
        if (cache.closestStopId == stopId && cache.departures.isNotEmpty()) {
            Pair(cache.departures, cache.departuresFetchedAt ?: System.currentTimeMillis())
        } else {
            throw e
        }
    }
}
