package dev.catchthenext.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.catchthenext.model.Stop
import dev.catchthenext.storage.FavoritesManager
import dev.catchthenext.android.location.CurrentLocationProvider
import dev.catchthenext.android.location.LatLon
import dev.catchthenext.android.storage.DistanceUnit
import dev.catchthenext.android.storage.localeDefaultUnit
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
    private val highAccuracyLocate: CurrentLocationProvider = locationProvider,
    distanceUnitFlow: Flow<DistanceUnit>,
    private val persistUnit: suspend (DistanceUnit) -> Unit,
    private val readAlertsByStopId: (suspend () -> Map<Long, Boolean>)? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    val favorites: StateFlow<List<Stop>> = favoritesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _alertsByStopId = MutableStateFlow<Map<Long, Boolean>>(emptyMap())
    val alertsByStopId: StateFlow<Map<Long, Boolean>> = _alertsByStopId.asStateFlow()

    private val _location = MutableStateFlow<LatLon?>(null)
    val location: StateFlow<LatLon?> = _location.asStateFlow()

    // Location age tracking — easy to remove if not needed in prod
    private val _locationFetchedAt = MutableStateFlow<Long?>(null)
    val locationFetchedAt: StateFlow<Long?> = _locationFetchedAt.asStateFlow()

    private val _locationRefreshing = MutableStateFlow(false)
    val locationRefreshing: StateFlow<Boolean> = _locationRefreshing.asStateFlow()

    val distanceUnit: StateFlow<DistanceUnit> = distanceUnitFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), localeDefaultUnit())

    init {
        fetchLocation(locationProvider)
        if (readAlertsByStopId != null) {
            viewModelScope.launch(ioDispatcher) {
                _alertsByStopId.value = readAlertsByStopId()
            }
        }
    }

    fun removeFavorite(onestopId: String) {
        viewModelScope.launch(ioDispatcher) {
            favoritesManager.removeFavorite(onestopId)
        }
    }

    fun toggleUnit() {
        viewModelScope.launch(ioDispatcher) {
            val next = if (distanceUnit.value == DistanceUnit.MILES) DistanceUnit.KM else DistanceUnit.MILES
            persistUnit(next)
        }
    }

    /** Forces a HIGH-accuracy GPS fix and updates location + age display. */
    fun refreshLocation() {
        if (_locationRefreshing.value) return
        fetchLocation(highAccuracyLocate, refreshing = true)
    }

    private fun fetchLocation(provider: CurrentLocationProvider, refreshing: Boolean = false) {
        viewModelScope.launch(ioDispatcher) {
            if (refreshing) _locationRefreshing.value = true
            val loc = provider.currentLocation()
            _location.value = loc
            _locationFetchedAt.value = if (loc != null) System.currentTimeMillis() else null
            if (refreshing) _locationRefreshing.value = false
        }
    }
}
