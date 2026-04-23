package dev.catchthenext.wear.ui

import dev.catchthenext.model.Stop
import dev.catchthenext.storage.FavoritesManager

class FakeFavoritesManager(initial: List<Stop> = emptyList()) : FavoritesManager {
    private val data = initial.toMutableList()

    override fun getFavorites(): List<Stop> = data.toList()

    override fun saveFavorites(stops: List<Stop>) {
        data.clear()
        data.addAll(stops)
    }

    override fun addFavorite(stop: Stop) {
        if (data.none { it.id == stop.id }) data.add(stop)
    }

    override fun removeFavorite(stopId: Long): Boolean = data.removeAll { it.id == stopId }

    override fun isFavorite(stopId: Long): Boolean = data.any { it.id == stopId }
}
