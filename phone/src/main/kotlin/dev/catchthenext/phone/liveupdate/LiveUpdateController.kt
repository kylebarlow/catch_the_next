package dev.catchthenext.phone.liveupdate

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dev.catchthenext.model.Stop
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LiveUpdateController(
    private val ctx: Context,
    private val store: LiveUpdateStateStore,
) {
    private val scope = CoroutineScope(Dispatchers.Default)

    val trackingState: StateFlow<TrackingState?> = store.flow
        .stateIn(scope, SharingStarted.Eagerly, null)

    fun start(stop: Stop) {
        val state = TrackingState(
            onestopId = stop.onestopId,
            stopId = stop.id,
            stopName = stop.stopName,
            stopLat = stop.lat,
            stopLon = stop.lon,
            startedAt = System.currentTimeMillis(),
            firstDepartureEtaEpochMs = null,
            gotWithin100m = false,
        )
        scope.launch { store.save(state) }
        val intent = Intent(ctx, LiveUpdateService::class.java).apply {
            putExtra(LiveUpdateService.EXTRA_ONESTOP_ID, state.onestopId)
            putExtra(LiveUpdateService.EXTRA_STOP_ID, state.stopId)
            putExtra(LiveUpdateService.EXTRA_STOP_NAME, state.stopName)
            putExtra(LiveUpdateService.EXTRA_STOP_LAT, state.stopLat)
            putExtra(LiveUpdateService.EXTRA_STOP_LON, state.stopLon)
        }
        ContextCompat.startForegroundService(ctx, intent)
    }

    fun stop() {
        scope.launch { store.clear() }
        ctx.stopService(Intent(ctx, LiveUpdateService::class.java))
    }
}
