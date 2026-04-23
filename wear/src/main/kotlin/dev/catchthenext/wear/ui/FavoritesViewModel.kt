package dev.catchthenext.wear.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.catchthenext.model.Stop
import dev.catchthenext.storage.FavoritesManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class FavoritesViewModel(
    favoritesFlow: Flow<List<Stop>>,
    private val favoritesManager: FavoritesManager,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    val favorites: StateFlow<List<Stop>> = favoritesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun removeFavorite(stopId: Long) {
        viewModelScope.launch(ioDispatcher) {
            favoritesManager.removeFavorite(stopId)
        }
    }
}
