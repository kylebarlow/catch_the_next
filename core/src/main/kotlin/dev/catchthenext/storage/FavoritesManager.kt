package dev.catchthenext.storage

import dev.catchthenext.model.Stop
import kotlinx.coroutines.flow.Flow

interface FavoritesManager {
    fun getFavorites(): List<Stop>
    fun saveFavorites(stops: List<Stop>)
    fun addFavorite(stop: Stop)
    fun removeFavorite(stopId: Long): Boolean
    fun isFavorite(stopId: Long): Boolean
    fun favoritesFlow(): Flow<List<Stop>>
}
