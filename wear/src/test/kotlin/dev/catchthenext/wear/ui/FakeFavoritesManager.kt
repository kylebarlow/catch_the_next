package dev.catchthenext.wear.ui

import dev.catchthenext.model.Stop
import dev.catchthenext.storage.FavoritesManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

class FakeFavoritesManager(initial: List<Stop> = emptyList()) : FavoritesManager {
    private val _data = MutableStateFlow(initial.toList())

    fun favoritesFlow(): Flow<List<Stop>> = _data.asStateFlow()

    override fun getFavorites(): List<Stop> = _data.value

    override fun saveFavorites(stops: List<Stop>) {
        _data.value = stops.toList()
    }

    override fun addFavorite(stop: Stop) {
        if (_data.value.none { it.id == stop.id }) {
            _data.value = _data.value + stop
        }
    }

    override fun removeFavorite(stopId: Long): Boolean {
        val before = _data.value
        _data.value = before.filter { it.id != stopId }
        return _data.value.size < before.size
    }

    override fun isFavorite(stopId: Long): Boolean = _data.value.any { it.id == stopId }
}
