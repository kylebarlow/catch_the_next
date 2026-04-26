package dev.catchthenext.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.catchthenext.android.storage.DistanceUnit
import dev.catchthenext.android.storage.localeDefaultUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    distanceUnitFlow: Flow<DistanceUnit>,
    private val persistUnit: suspend (DistanceUnit) -> Unit,
    thresholdMetersFlow: Flow<Int>,
    private val persistThreshold: suspend (Int) -> Unit,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    val distanceUnit: StateFlow<DistanceUnit> = distanceUnitFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), localeDefaultUnit())

    val thresholdMeters: StateFlow<Int> = thresholdMetersFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 1609)

    fun setUnit(unit: DistanceUnit) {
        viewModelScope.launch(ioDispatcher) { persistUnit(unit) }
    }

    fun setThresholdMeters(meters: Int) {
        viewModelScope.launch(ioDispatcher) { persistThreshold(meters) }
    }
}
