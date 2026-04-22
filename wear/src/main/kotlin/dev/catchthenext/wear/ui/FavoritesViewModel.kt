package dev.catchthenext.wear.ui

import androidx.lifecycle.ViewModel
import dev.catchthenext.model.Stop
import dev.catchthenext.storage.FavoritesManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class FavoritesViewModel(private val favoritesManager: FavoritesManager) : ViewModel() {
    private val _favorites = MutableStateFlow<List<Stop>>(emptyList())
    val favorites: StateFlow<List<Stop>> = _favorites

    init {
        loadFavorites()
    }

    fun loadFavorites() {
        _favorites.value = favoritesManager.getFavorites()
    }
}
