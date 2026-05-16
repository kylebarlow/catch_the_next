package dev.catchthenext.phone.liveupdate

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import dev.catchthenext.android.location.haversineMeters
import dev.catchthenext.android.location.locationUpdates
import dev.catchthenext.android.tile.CachedDeparture
import dev.catchthenext.phone.PhoneGraph
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class LiveUpdateService : Service() {

    companion object {
        const val EXTRA_ONESTOP_ID = "onestop_id"
        const val EXTRA_STOP_ID = "stop_id"
        const val EXTRA_STOP_NAME = "stop_name"
        const val EXTRA_STOP_LAT = "stop_lat"
        const val EXTRA_STOP_LON = "stop_lon"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var trackingJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureLiveUpdateChannel(this)
        ServiceCompat.startForeground(
            this,
            LIVE_UPDATE_NOTIF_ID,
            buildLiveUpdateNotification(this, placeholderState(), emptyList()),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val onestopId = intent?.getStringExtra(EXTRA_ONESTOP_ID)
        val stopId = intent?.getLongExtra(EXTRA_STOP_ID, -1L) ?: -1L
        val stopName = intent?.getStringExtra(EXTRA_STOP_NAME) ?: "Stop"
        val stopLat = intent?.getDoubleExtra(EXTRA_STOP_LAT, 0.0) ?: 0.0
        val stopLon = intent?.getDoubleExtra(EXTRA_STOP_LON, 0.0) ?: 0.0

        if (stopId == -1L) {
            stopSelf()
            return START_NOT_STICKY
        }

        val newState = TrackingState(
            onestopId = onestopId,
            stopId = stopId,
            stopName = stopName,
            stopLat = stopLat,
            stopLon = stopLon,
            startedAt = System.currentTimeMillis(),
            firstDepartureEtaEpochMs = null,
            gotWithin100m = false,
        )

        trackingJob?.cancel()
        trackingJob = serviceScope.launch { runTracking(newState) }

        return START_REDELIVER_INTENT
    }

    private suspend fun runTracking(initialState: TrackingState) {
        var state = initialState
        val store = PhoneGraph.liveUpdateStateStore(this@LiveUpdateService)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val client = PhoneGraph.transitlandClient()
        val dismissSignal = CompletableDeferred<Unit>()

        var cachedDepartures: List<CachedDeparture> = emptyList()
        var lastFetchMs = 0L

        val refreshJob = serviceScope.launch {
            while (!dismissSignal.isCompleted) {
                val nowMs = System.currentTimeMillis()
                if (nowMs - lastFetchMs >= 60_000L && state.onestopId != null) {
                    runCatching {
                        val result = client.getDeparturesBatch(listOf(state.onestopId!!))
                        val fetchTime = System.currentTimeMillis()
                        result[state.onestopId]?.departures?.map { dep ->
                            CachedDeparture(
                                dep.routeShortName, dep.headsign,
                                fetchTime + dep.displayDepartureMinutes * 60_000,
                                dep.timeSource, dep.agencyName,
                            )
                        }
                    }.getOrNull()?.let {
                        cachedDepartures = it.filter { d -> d.currentMinutes() >= 0 }
                            .sortedBy { d -> d.departureEpochMillis }
                        lastFetchMs = System.currentTimeMillis()

                        if (state.firstDepartureEtaEpochMs == null) {
                            cachedDepartures.firstOrNull()?.let { first ->
                                state = state.copy(firstDepartureEtaEpochMs = first.departureEpochMillis)
                                store.save(state)
                            }
                        }
                    }
                }
                nm.notify(LIVE_UPDATE_NOTIF_ID, buildLiveUpdateNotification(this@LiveUpdateService, state, cachedDepartures))
                delay(30_000L)
            }
        }

        val locationJob = serviceScope.launch {
            locationUpdates(this@LiveUpdateService, intervalMs = 10_000L).collect { latLon ->
                val dist = haversineMeters(latLon.lat, latLon.lon, state.stopLat, state.stopLon)
                if (!state.gotWithin100m && dist <= 100.0) {
                    state = state.copy(gotWithin100m = true)
                    store.save(state)
                }
                if (state.gotWithin100m && dist > 250.0) {
                    dismissSignal.complete(Unit)
                }
            }
        }

        val watchdogJob = serviceScope.launch {
            while (!dismissSignal.isCompleted) {
                delay(30_000L)
                val nowMs = System.currentTimeMillis()
                val cap = state.firstDepartureEtaEpochMs
                    ?.let { maxOf(state.startedAt + 60 * 60_000L, it + 10 * 60_000L) }
                    ?: (state.startedAt + 60 * 60_000L)
                if (nowMs >= cap) dismissSignal.complete(Unit)
            }
        }

        try {
            dismissSignal.await()
        } finally {
            refreshJob.cancel()
            locationJob.cancel()
            watchdogJob.cancel()
        }

        store.clear()
        nm.cancel(LIVE_UPDATE_NOTIF_ID)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    private fun placeholderState() = TrackingState(
        onestopId = null, stopId = 0, stopName = "Loading…",
        stopLat = 0.0, stopLon = 0.0,
        startedAt = System.currentTimeMillis(),
        firstDepartureEtaEpochMs = null, gotWithin100m = false,
    )
}
