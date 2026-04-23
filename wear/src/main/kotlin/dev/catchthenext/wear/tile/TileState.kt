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

// fetchDepartures returns (departures, fetchedAt epoch millis). Throw to signal NetworkError.
suspend fun computeTileState(
    favorites: List<Stop>,
    location: LatLon?,
    hasPermission: Boolean,
    fetchDepartures: suspend (Long) -> Pair<List<CachedDeparture>, Long>,
): TileState {
    if (!hasPermission) return TileState.NoPermission
    if (favorites.isEmpty()) return TileState.NoFavorites
    if (location == null) return TileState.NoLocation
    val closest = favorites.closestTo(location.lat, location.lon)
        ?: return TileState.NoFavorites
    return runCatching {
        val (deps, fetchedAt) = fetchDepartures(closest.id)
        TileState.Ready(closest, deps.filter { it.currentMinutes() >= 0 }, fetchedAt)
    }.getOrElse { TileState.NetworkError(it.message ?: "Network error") }
}
