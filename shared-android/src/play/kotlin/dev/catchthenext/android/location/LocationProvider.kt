package dev.catchthenext.android.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import dev.catchthenext.android.tile.Tuning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val MAX_LAST_LOCATION_AGE_MS = 2 * 60 * 1000L
private const val MAX_ACCURACY_METERS = 500f

class LocationProvider(private val context: Context) : CurrentLocationProvider, QuickLocationProvider {
    override suspend fun currentLocation(): LatLon? = locate(LocationMode.PASSIVE)

    /**
     * The cached fix, or a usable last-known one. Never waits on a fresh fix, so callers can get
     * their network request in flight while [locate] refines the position in parallel.
     */
    override suspend fun quickLocation(): LatLon? = withContext(Dispatchers.IO) {
        val hasPerm = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPerm) return@withContext null
        LocationCache.get()?.let { return@withContext it }
        lastKnownUsable()?.also { LocationCache.put(it) }
    }

    private suspend fun lastKnownUsable(): LatLon? = runCatching {
        LocationServices.getFusedLocationProviderClient(context).lastLocation.await()
            ?.takeIf { it.isUsable() }
            ?.let { LatLon(it.latitude, it.longitude) }
    }.getOrNull()

    suspend fun locate(mode: LocationMode): LatLon? = withContext(Dispatchers.IO) {
        val hasPerm = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPerm) return@withContext null

        // For automatic/background fetches, return cached location if less than 1 minute old.
        if (mode == LocationMode.PASSIVE) {
            LocationCache.get()?.let { return@withContext it }
        }

        val client = LocationServices.getFusedLocationProviderClient(context)
        val result = runCatching {
            when (mode) {
                LocationMode.PASSIVE -> {
                    val last = client.lastLocation.await()
                    if (last != null && last.isUsable()) LatLon(last.latitude, last.longitude)
                    else client.fetchFresh(Priority.PRIORITY_BALANCED_POWER_ACCURACY, timeoutMs = Tuning.PASSIVE_LOCATION_TIMEOUT_MS)
                }
                LocationMode.HIGH -> {
                    client.fetchFresh(Priority.PRIORITY_HIGH_ACCURACY, timeoutMs = 20_000L)
                        ?: client.lastLocation.await()
                            ?.takeIf { it.isUsable() }
                            ?.let { LatLon(it.latitude, it.longitude) }
                }
            }
        }.getOrNull()
        result?.also { LocationCache.put(it) }
        result
    }
}

private suspend fun FusedLocationProviderClient.fetchFresh(priority: Int, timeoutMs: Long): LatLon? {
    val cts = CancellationTokenSource()
    return try {
        val loc = withTimeoutOrNull(timeoutMs) {
            getCurrentLocation(priority, cts.token).await()
        }
        loc?.let { LatLon(it.latitude, it.longitude) }
    } finally {
        cts.cancel()
    }
}

private fun Location.isUsable(): Boolean {
    val ageMs = (SystemClock.elapsedRealtimeNanos() - elapsedRealtimeNanos) / 1_000_000
    return ageMs <= MAX_LAST_LOCATION_AGE_MS && (accuracy <= 0f || accuracy <= MAX_ACCURACY_METERS)
}
