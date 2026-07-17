package dev.catchthenext.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.catchthenext.api.StopDepartures
import dev.catchthenext.model.Alert
import dev.catchthenext.model.Departure
import dev.catchthenext.model.Stop
import dev.catchthenext.android.util.toNetworkMessage
import dev.catchthenext.storage.FavoritesManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface DetailsUi {
    object Loading : DetailsUi
    data class Loaded(val stop: Stop, val departures: List<Departure>, val isFavorite: Boolean, val alerts: List<Alert> = emptyList()) : DetailsUi {
        /** Distinct display names of the feeds contributing to this stop's data. */
        fun feedNames(): List<String> =
            (listOfNotNull(stop.feed) + departures.mapNotNull { it.feed })
                .distinctBy { it.feedOnestopId }
                .mapNotNull { it.feedName ?: it.feedOnestopId }

        /** Route short names available at this stop, for the per-favorite filter editor. */
        fun availableRoutes(): List<String> =
            (departures.map { it.routeShortName } + stop.routesServed.orEmpty() + stop.shownRoutes.orEmpty())
                .filter { it.isNotBlank() }
                .distinct()
                .sorted()
    }
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
            }.getOrElse { DetailsUi.Error(it.toNetworkMessage()) }
        }
    }

    fun toggleFavorite() {
        val current = _ui.value as? DetailsUi.Loaded ?: return
        viewModelScope.launch(ioDispatcher) {
            if (current.isFavorite) {
                favoritesManager.removeFavorite(current.stop)
            } else {
                favoritesManager.addFavorite(current.stop)
            }
            _ui.value = current.copy(isFavorite = !current.isFavorite)
        }
    }

    /** Persists the per-favorite route filter. Null or empty = show all routes. */
    fun setShownRoutes(routes: List<String>?) {
        updateStop { it.copy(shownRoutes = routes?.takeIf { r -> r.isNotEmpty() }) }
    }

    /** Persists the favorite's nickname. Null or blank = use the GTFS stop name. */
    fun setNickname(nickname: String?) {
        updateStop { it.copy(nickname = nickname?.trim()?.takeIf { n -> n.isNotEmpty() }) }
    }

    private fun updateStop(transform: (Stop) -> Stop) {
        val current = _ui.value as? DetailsUi.Loaded ?: return
        val updated = transform(current.stop)
        _ui.value = current.copy(stop = updated)
        if (!current.isFavorite) return
        viewModelScope.launch(ioDispatcher) {
            favoritesManager.updateFavorite(updated)
        }
    }
}
