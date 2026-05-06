package dev.catchthenext.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.catchthenext.api.StopDepartures
import dev.catchthenext.model.Alert
import dev.catchthenext.model.Departure
import dev.catchthenext.model.Stop
import dev.catchthenext.storage.FavoritesManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface DetailsUi {
    object Loading : DetailsUi
    data class Loaded(val stop: Stop, val departures: List<Departure>, val isFavorite: Boolean, val alerts: List<Alert> = emptyList()) : DetailsUi
    data class Error(val msg: String) : DetailsUi
}

class StopDetailsViewModel(
    private val getDepartures: suspend (Long) -> StopDepartures,
    private val favoritesManager: FavoritesManager,
    val stopId: Long,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _ui = MutableStateFlow<DetailsUi>(DetailsUi.Loading)
    val ui: StateFlow<DetailsUi> = _ui

    init {
        refresh()
    }

    fun refresh() {
        _ui.value = DetailsUi.Loading
        viewModelScope.launch(ioDispatcher) {
            _ui.value = runCatching {
                val favorites = favoritesManager.getFavorites()
                val stop = favorites.firstOrNull { it.id == stopId }
                    ?: Stop(stopId, stopId.toString(), "Stop $stopId", 0.0, 0.0)
                val stopDeps = getDepartures(stopId)
                val isFav = favoritesManager.isFavorite(stop.onestopId ?: "")
                DetailsUi.Loaded(stop, stopDeps.departures, isFav, stopDeps.alerts)
            }.getOrElse { DetailsUi.Error(it.message ?: "Error loading departures") }
        }
    }

    fun toggleFavorite() {
        val current = _ui.value as? DetailsUi.Loaded ?: return
        viewModelScope.launch(ioDispatcher) {
            if (current.isFavorite) {
                favoritesManager.removeFavorite(current.stop.onestopId ?: "")
            } else {
                favoritesManager.addFavorite(current.stop)
            }
            _ui.value = current.copy(isFavorite = !current.isFavorite)
        }
    }
}
