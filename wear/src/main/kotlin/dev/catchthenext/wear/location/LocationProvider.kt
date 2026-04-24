package dev.catchthenext.wear.location

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val MAX_LAST_LOCATION_AGE_MS = 2 * 60 * 1000L
private const val MAX_ACCURACY_METERS = 500f

enum class LocationMode { PASSIVE, HIGH }

fun interface CurrentLocationProvider {
    suspend fun currentLocation(): LatLon?
}

/** Returns a [CurrentLocationProvider] that always forces a HIGH-accuracy GPS fix. */
fun LocationProvider.asHighAccuracy(): CurrentLocationProvider =
    CurrentLocationProvider { locate(LocationMode.HIGH) }

class LocationProvider(private val context: Context) : CurrentLocationProvider {
    override suspend fun currentLocation(): LatLon? = locate(LocationMode.PASSIVE)

    suspend fun locate(mode: LocationMode): LatLon? = withContext(Dispatchers.IO) {
        val hasPerm = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPerm) return@withContext null

        val client = LocationServices.getFusedLocationProviderClient(context)
        runCatching {
            when (mode) {
                LocationMode.PASSIVE -> {
                    val last = client.lastLocation.await()
                    if (last != null && last.isUsable()) LatLon(last.latitude, last.longitude)
                    else client.fetchFresh(Priority.PRIORITY_BALANCED_POWER_ACCURACY, timeoutMs = 8_000L)
                }
                LocationMode.HIGH -> {
                    client.fetchFresh(Priority.PRIORITY_HIGH_ACCURACY, timeoutMs = 20_000L)
                        ?: client.lastLocation.await()
                            ?.takeIf { it.isUsable() }
                            ?.let { LatLon(it.latitude, it.longitude) }
                }
            }
        }.getOrNull()
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
