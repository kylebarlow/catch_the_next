package dev.catchthenext.android.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.SystemClock
import android.util.Log
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

/**
 * [gpsFallback] enables one bounded high-accuracy attempt after a balanced-power fix comes back
 * empty (wear only: a watch away from its phone and off WiFi cannot be located any other way).
 * It is rate-limited by [Tuning.GPS_FALLBACK_MIN_INTERVAL_MS] and skipped when the OS already
 * holds a recent fix.
 */
class LocationProvider(
    private val context: Context,
    private val gpsFallback: Boolean = false,
) : CurrentLocationProvider, QuickLocationProvider {
    override suspend fun currentLocation(): LatLon? = locate(LocationMode.PASSIVE)

    /**
     * The cached fix, or the last-known one at *any* age, timestamped so the caller can weigh it
     * against its own persisted position. Never waits on a fresh fix, so callers can get their
     * network request in flight while [locate] refines the position in parallel.
     */
    override suspend fun quickLocation(): LocationFix? = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext null
        LocationCache.getFix()?.let { return@withContext it }
        lastKnownFix()
    }

    /**
     * The last-known fix regardless of age (bounded only by accuracy). Only seeds [LocationCache]
     * when it is fresh enough to be usable, so a stale fix never short-circuits [locate].
     */
    private suspend fun lastKnownFix(): LocationFix? = runCatching {
        val loc = LocationServices.getFusedLocationProviderClient(context).lastLocation.await()
            ?.takeIf { it.accuracy <= 0f || it.accuracy <= MAX_ACCURACY_METERS }
            ?: return@runCatching null
        val latLon = LatLon(loc.latitude, loc.longitude)
        if (loc.isUsable()) LocationCache.put(latLon)
        LocationFix(latLon, loc.time)
    }.getOrNull()

    suspend fun locate(mode: LocationMode): LatLon? = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext null

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
                        ?: client.gpsFallbackOrNull()
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

    private suspend fun FusedLocationProviderClient.gpsFallbackOrNull(): LatLon? {
        if (!gpsFallback || !shouldTryGps()) return null
        LocationCache.markGpsAttempt()
        Log.d("Departures", "gps fallback: attempting")
        val fix = fetchFresh(Priority.PRIORITY_HIGH_ACCURACY, timeoutMs = Tuning.GPS_FALLBACK_TIMEOUT_MS)
        Log.d("Departures", "gps fallback: ${if (fix != null) "ok" else "none"}")
        return fix
    }

    /** Only worth the battery when nothing anywhere has a recent fix, and not too often. */
    private suspend fun FusedLocationProviderClient.shouldTryGps(): Boolean {
        val sinceAttempt = System.currentTimeMillis() - LocationCache.lastGpsAttemptAt
        if (sinceAttempt < Tuning.GPS_FALLBACK_MIN_INTERVAL_MS) return false
        val last = runCatching { lastLocation.await() }.getOrNull() ?: return true
        return last.ageMs() > Tuning.GPS_FALLBACK_MIN_FIX_AGE_MS
    }

    private fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_COARSE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
}

private suspend fun FusedLocationProviderClient.fetchFresh(priority: Int, timeoutMs: Long): LatLon? {
    val cts = CancellationTokenSource()
    val startedAt = SystemClock.elapsedRealtime()
    return try {
        val loc = withTimeoutOrNull(timeoutMs) {
            getCurrentLocation(priority, cts.token).await()
        }
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        if (loc == null) Log.d("Departures", "fresh fix none after ${elapsed}ms (priority=$priority)")
        else Log.d("Departures", "fresh fix ok in ${elapsed}ms (priority=$priority)")
        loc?.let { LatLon(it.latitude, it.longitude) }
    } catch (t: Throwable) {
        Log.d("Departures", "fresh fix failed after ${SystemClock.elapsedRealtime() - startedAt}ms: $t")
        throw t
    } finally {
        cts.cancel()
    }
}

private fun Location.ageMs(): Long =
    (SystemClock.elapsedRealtimeNanos() - elapsedRealtimeNanos) / 1_000_000

private fun Location.isUsable(): Boolean =
    ageMs() <= MAX_LAST_LOCATION_AGE_MS && (accuracy <= 0f || accuracy <= MAX_ACCURACY_METERS)
