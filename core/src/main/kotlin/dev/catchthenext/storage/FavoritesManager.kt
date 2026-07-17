package dev.catchthenext.storage

import dev.catchthenext.model.Stop
import kotlinx.coroutines.flow.Flow

interface FavoritesManager {
    fun getFavorites(): List<Stop>
    fun saveFavorites(stops: List<Stop>)
    fun addFavorite(stop: Stop)
    fun removeFavorite(onestopId: String): Boolean
    fun isFavorite(onestopId: String): Boolean
    fun favoritesFlow(): Flow<List<Stop>>

    /**
     * Removes [stop], falling back to matching by internal id when it has no onestop id
     * (which the onestop-keyed [removeFavorite] can't target).
     */
    fun removeFavorite(stop: Stop): Boolean {
        val onestopId = stop.onestopId
        if (onestopId != null) return removeFavorite(onestopId)
        val remaining = getFavorites().filterNot { it.id == stop.id }
        if (remaining.size == getFavorites().size) return false
        saveFavorites(remaining)
        return true
    }

    /**
     * Replaces the stored favorite matching [stop] (by onestop id, else internal id) with
     * [stop] — used to persist per-favorite settings like nickname and route filter.
     */
    fun updateFavorite(stop: Stop) {
        saveFavorites(getFavorites().map { existing ->
            val matches = (stop.onestopId != null && existing.onestopId == stop.onestopId) ||
                (stop.onestopId == null && existing.id == stop.id)
            if (matches) stop else existing
        })
    }
}
