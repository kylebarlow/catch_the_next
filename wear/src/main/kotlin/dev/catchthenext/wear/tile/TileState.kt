package dev.catchthenext.wear.tile

import dev.catchthenext.model.Departure
import dev.catchthenext.model.Stop
import dev.catchthenext.wear.location.LatLon
import dev.catchthenext.wear.location.closestTo

sealed interface TileState {
    object NoFavorites : TileState
    object NoPermission : TileState
    object NoLocation : TileState
    data class NetworkError(val message: String) : TileState
    data class Ready(val stop: Stop, val departures: List<Departure>) : TileState
}

suspend fun computeTileState(
    favorites: List<Stop>,
    location: LatLon?,
    hasPermission: Boolean,
    fetchDepartures: suspend (Long) -> List<Departure>,
): TileState {
    if (!hasPermission) return TileState.NoPermission
    if (favorites.isEmpty()) return TileState.NoFavorites
    if (location == null) return TileState.NoLocation
    val closest = favorites.closestTo(location.lat, location.lon)
        ?: return TileState.NoFavorites
    return runCatching {
        TileState.Ready(closest, fetchDepartures(closest.id))
    }.getOrElse { TileState.NetworkError(it.message ?: "Network error") }
}
