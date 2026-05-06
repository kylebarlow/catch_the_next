package dev.catchthenext.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.catchthenext.android.storage.DistanceUnit
import dev.catchthenext.android.storage.localeDefaultUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class SyncStatus { IDLE, SYNCING, SUCCESS, ERROR }

class SettingsViewModel(
    distanceUnitFlow: Flow<DistanceUnit>,
    private val persistUnit: suspend (DistanceUnit) -> Unit,
    thresholdMetersFlow: Flow<Int>,
    private val persistThreshold: suspend (Int) -> Unit,
    private val triggerSync: suspend () -> Unit = {},
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    val distanceUnit: StateFlow<DistanceUnit> = distanceUnitFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), localeDefaultUnit())

    val thresholdMeters: StateFlow<Int> = thresholdMetersFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 1609)

    private val _syncStatus = MutableStateFlow(SyncStatus.IDLE)
    val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    private val _syncEvents = Channel<String>(Channel.BUFFERED)
    val syncEvents: Flow<String> = _syncEvents.receiveAsFlow()

    fun setUnit(unit: DistanceUnit) {
        viewModelScope.launch(ioDispatcher) { persistUnit(unit) }
    }

    fun setThresholdMeters(meters: Int) {
        viewModelScope.launch(ioDispatcher) { persistThreshold(meters) }
    }

    fun syncNow() {
        viewModelScope.launch(ioDispatcher) {
            _syncStatus.value = SyncStatus.SYNCING
            runCatching { triggerSync() }
                .onSuccess {
                    _syncStatus.value = SyncStatus.SUCCESS
                    _syncEvents.trySend("Synced")
                }
                .onFailure {
                    _syncStatus.value = SyncStatus.ERROR
                    _syncEvents.trySend("Sync failed")
                }
            delay(2_000)
            _syncStatus.value = SyncStatus.IDLE
        }
    }
}
