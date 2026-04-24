package dev.catchthenext.wear.tile

import dev.catchthenext.api.TransitlandClient
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

suspend fun updateClosestStopDepartures(
    lat: Double,
    lon: Double,
    favorites: List<Stop>,
    dataStore: TileDataStore,
    client: TransitlandClient,
    cache: CachedTileData,
): TileState {
    val closest = favorites.closestTo(lat, lon) ?: return TileState.NoFavorites
    return runCatching {
        val deps = client.getDepartures(closest.id)
        val now = System.currentTimeMillis()
        val cached = deps.map { dep ->
            CachedDeparture(
                routeShortName = dep.routeShortName,
                headsign = dep.headsign,
                scheduledEpochMillis = now + dep.departureMinutes * 60_000
            )
        }
        dataStore.updateDepartures(closest, cached)
        TileState.Ready(closest, cached.filter { it.currentMinutes() >= 0 }, now)
    }.getOrElse {
        if (cache.closestStopId == closest.id && cache.departures.isNotEmpty()) {
            TileState.Ready(
                closest,
                cache.departures.filter { it.currentMinutes() >= 0 },
                cache.departuresFetchedAt ?: System.currentTimeMillis()
            )
        } else {
            TileState.NetworkError(it.message ?: "Network error")
        }
    }
}

suspend fun computeTileState(
    favorites: List<Stop>,
    location: LatLon?,
    hasPermission: Boolean,
    dataStore: TileDataStore,
    client: TransitlandClient,
    cache: CachedTileData,
): TileState {
    if (!hasPermission) return TileState.NoPermission
    if (favorites.isEmpty()) return TileState.NoFavorites
    if (location == null) return TileState.NoLocation
    return updateClosestStopDepartures(location.lat, location.lon, favorites, dataStore, client, cache)
}
