package dev.catchthenext.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.catchthenext.model.Alert
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class StopAlertsUi(
    val stopName: String?,
    val alerts: List<Alert>,
)

/**
 * Renders the cached service alerts for a single stop. Reads only from the local
 * [dev.catchthenext.android.tile.TileDataStore] cache — never hits the network.
 */
class StopAlertsViewModel(
    private val stopId: Long,
    private val readAlerts: suspend (Long) -> List<Alert>,
    private val getStopName: suspend (Long) -> String?,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _ui = MutableStateFlow(StopAlertsUi(stopName = null, alerts = emptyList()))
    val ui: StateFlow<StopAlertsUi> = _ui.asStateFlow()

    init {
        viewModelScope.launch(ioDispatcher) {
            _ui.value = StopAlertsUi(
                stopName = getStopName(stopId),
                alerts = readAlerts(stopId),
            )
        }
    }
}
