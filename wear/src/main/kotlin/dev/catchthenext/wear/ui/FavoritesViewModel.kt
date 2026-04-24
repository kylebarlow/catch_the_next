package dev.catchthenext.wear.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.catchthenext.model.Stop
import dev.catchthenext.storage.FavoritesManager
import dev.catchthenext.wear.location.CurrentLocationProvider
import dev.catchthenext.wear.location.LatLon
import dev.catchthenext.wear.storage.DistanceUnit
import dev.catchthenext.wear.storage.DistanceUnitStore
import dev.catchthenext.wear.storage.localeDefaultUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class FavoritesViewModel(
    favoritesFlow: Flow<List<Stop>>,
    private val favoritesManager: FavoritesManager,
    private val locationProvider: CurrentLocationProvider,
    private val distanceUnitStore: DistanceUnitStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    val favorites: StateFlow<List<Stop>> = favoritesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _location = MutableStateFlow<LatLon?>(null)
    val location: StateFlow<LatLon?> = _location.asStateFlow()

    val distanceUnit: StateFlow<DistanceUnit> = distanceUnitStore.unitFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), localeDefaultUnit())

    init {
        viewModelScope.launch(ioDispatcher) {
            _location.value = locationProvider.currentLocation()
        }
    }

    fun removeFavorite(stopId: Long) {
        viewModelScope.launch(ioDispatcher) {
            favoritesManager.removeFavorite(stopId)
        }
    }

    fun toggleUnit() {
        viewModelScope.launch(ioDispatcher) {
            val next = if (distanceUnit.value == DistanceUnit.MILES) DistanceUnit.KM else DistanceUnit.MILES
            distanceUnitStore.setUnit(next)
        }
    }
}
