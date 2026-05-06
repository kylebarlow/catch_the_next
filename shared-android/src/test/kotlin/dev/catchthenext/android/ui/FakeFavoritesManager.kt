package dev.catchthenext.android.ui

import dev.catchthenext.model.Stop
import dev.catchthenext.storage.FavoritesManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeFavoritesManager(initial: List<Stop> = emptyList()) : FavoritesManager {
    private val _data = MutableStateFlow(initial.toList())

    override fun favoritesFlow(): Flow<List<Stop>> = _data.asStateFlow()

    override fun getFavorites(): List<Stop> = _data.value

    override fun saveFavorites(stops: List<Stop>) {
        _data.value = stops.toList()
    }

    override fun addFavorite(stop: Stop) {
        if (_data.value.none { it.id == stop.id }) {
            _data.value = _data.value + stop
        }
    }

    override fun removeFavorite(onestopId: String): Boolean {
        val before = _data.value
        _data.value = before.filter { it.onestopId != onestopId }
        return _data.value.size < before.size
    }

    override fun isFavorite(onestopId: String): Boolean = _data.value.any { it.onestopId == onestopId }
}
