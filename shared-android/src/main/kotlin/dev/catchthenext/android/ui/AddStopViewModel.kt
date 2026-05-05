package dev.catchthenext.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.catchthenext.model.Stop
import dev.catchthenext.storage.FavoritesManager
import dev.catchthenext.android.location.CurrentLocationProvider
import dev.catchthenext.android.location.LatLon
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface AddStopUi {
    object PermissionNeeded : AddStopUi
    object Locating : AddStopUi
    data class Loaded(val stops: List<Stop>) : AddStopUi
    object Empty : AddStopUi
    data class Error(val msg: String) : AddStopUi
}

class AddStopViewModel(
    private val getNearbyStops: suspend (Double, Double, Int) -> List<Stop>,
    private val favoritesManager: FavoritesManager,
    private val locationProvider: CurrentLocationProvider,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _ui = MutableStateFlow<AddStopUi>(AddStopUi.PermissionNeeded)
    val ui: StateFlow<AddStopUi> = _ui

    fun onPermissionGranted() {
        _ui.value = AddStopUi.Locating
        viewModelScope.launch(ioDispatcher) {
            val latLon: LatLon? = locationProvider.currentLocation()
            if (latLon == null) {
                _ui.value = AddStopUi.Error("Could not get location")
                return@launch
            }
            loadStopsAt(latLon, radiusMeters = 600)
        }
    }

    fun loadFor(latLon: LatLon, radiusMeters: Int = 1500) {
        _ui.value = AddStopUi.Locating
        viewModelScope.launch(ioDispatcher) {
            loadStopsAt(latLon, radiusMeters)
        }
    }

    private suspend fun loadStopsAt(latLon: LatLon, radiusMeters: Int) {
        _ui.value = runCatching {
            val stops = getNearbyStops(latLon.lat, latLon.lon, radiusMeters)
            if (stops.isEmpty()) AddStopUi.Empty else AddStopUi.Loaded(stops)
        }.getOrElse { AddStopUi.Error(it.message ?: "Network error") }
    }

    fun addStop(stop: Stop) {
        viewModelScope.launch(ioDispatcher) {
            favoritesManager.addFavorite(stop)
        }
    }
}
