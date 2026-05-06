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
}
