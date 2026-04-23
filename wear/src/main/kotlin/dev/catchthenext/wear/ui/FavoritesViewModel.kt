package dev.catchthenext.wear.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.catchthenext.model.Stop
import dev.catchthenext.storage.FavoritesManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class FavoritesViewModel(
    private val favoritesManager: FavoritesManager,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _favorites = MutableStateFlow<List<Stop>>(emptyList())
    val favorites: StateFlow<List<Stop>> = _favorites

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch(ioDispatcher) {
            _favorites.value = favoritesManager.getFavorites()
        }
    }

    fun removeFavorite(stopId: Long) {
        viewModelScope.launch(ioDispatcher) {
            favoritesManager.removeFavorite(stopId)
            _favorites.value = favoritesManager.getFavorites()
        }
    }
}
