package dev.catchthenext.android.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import dev.catchthenext.android.tile.Tuning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

private const val MAX_LAST_LOCATION_AGE_MS = 2 * 60 * 1000L
private const val MAX_ACCURACY_METERS = 500f

/**
 * AOSP (FOSS) twin of the play [LocationProvider]. Backed by the framework
 * [LocationManager] instead of Google Play Services' FusedLocation, so the fdroid
 * variant carries zero proprietary dependencies. Public API is identical to the play
 * class — call sites in `main` are unchanged, including [gpsFallback].
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
    private fun lastKnownFix(): LocationFix? = runCatching {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val loc = manager?.bestLastKnownLocation()
            ?.takeIf { it.accuracy <= 0f || it.accuracy <= MAX_ACCURACY_METERS }
            ?: return@runCatching null
        val latLon = LatLon(loc.latitude, loc.longitude)
        if (loc.isUsable()) LocationCache.put(latLon)
        LocationFix(latLon, loc.time)
    }.getOrNull()

    suspend fun locate(mode: LocationMode): LatLon? = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext null

        if (mode == LocationMode.PASSIVE) {
            LocationCache.get()?.let { return@withContext it }
        }

        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return@withContext null

        val result = runCatching {
            when (mode) {
                LocationMode.PASSIVE -> {
                    val last = manager.bestLastKnownLocation()
                    if (last != null && last.isUsable()) LatLon(last.latitude, last.longitude)
                    else manager.fetchFresh(
                        // Balanced-power twin: network first when it is available, GPS only as
                        // the (rate-limited) fallback below, matching the play flavour.
                        preferGps = false,
                        timeoutMs = Tuning.PASSIVE_LOCATION_TIMEOUT_MS,
                    ) ?: manager.gpsFallbackOrNull()
                }
                LocationMode.HIGH -> {
                    manager.fetchFresh(preferGps = true, timeoutMs = 20_000L)
                        ?: manager.bestLastKnownLocation()
                            ?.takeIf { it.isUsable() }
                            ?.let { LatLon(it.latitude, it.longitude) }
                }
            }
        }.getOrNull()
        result?.also { LocationCache.put(it) }
        result
    }

    private suspend fun LocationManager.gpsFallbackOrNull(): LatLon? {
        if (!gpsFallback || !shouldTryGps()) return null
        LocationCache.markGpsAttempt()
        Log.d("Departures", "gps fallback: attempting")
        val fix = fetchFresh(preferGps = true, timeoutMs = Tuning.GPS_FALLBACK_TIMEOUT_MS)
        Log.d("Departures", "gps fallback: ${if (fix != null) "ok" else "none"}")
        return fix
    }

    /** Only worth the battery when nothing anywhere has a recent fix, and not too often. */
    private fun LocationManager.shouldTryGps(): Boolean {
        val sinceAttempt = System.currentTimeMillis() - LocationCache.lastGpsAttemptAt
        if (sinceAttempt < Tuning.GPS_FALLBACK_MIN_INTERVAL_MS) return false
        val last = bestLastKnownLocation() ?: return true
        return last.ageMs() > Tuning.GPS_FALLBACK_MIN_FIX_AGE_MS
    }

    private fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_COARSE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
}

private fun LocationManager.bestLastKnownLocation(): Location? {
    val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
    return providers.mapNotNull { p ->
        runCatching { if (isProviderEnabled(p)) getLastKnownLocation(p) else null }.getOrNull()
    }.maxByOrNull { it.time }
}

/**
 * [preferGps] picks the provider: the high-accuracy paths ask for GPS, the balanced-power one
 * asks for the network provider and only falls back to GPS when the network provider is off.
 */
private suspend fun LocationManager.fetchFresh(preferGps: Boolean, timeoutMs: Long): LatLon? {
    val order = if (preferGps) {
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
    } else {
        listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
    }
    val provider = order.firstOrNull { runCatching { isProviderEnabled(it) }.getOrDefault(false) }
        ?: return null
    val signal = CancellationSignal()
    val startedAt = SystemClock.elapsedRealtime()
    return try {
        val fix = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<LatLon?> { cont ->
                cont.invokeOnCancellation { signal.cancel() }
                LocationManagerCompat.getCurrentLocation(
                    this@fetchFresh,
                    provider,
                    signal,
                    DIRECT_EXECUTOR,
                ) { loc ->
                    cont.resume(loc?.let { LatLon(it.latitude, it.longitude) })
                }
            }
        }
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        if (fix == null) Log.d("Departures", "fresh fix none after ${elapsed}ms (provider=$provider)")
        else Log.d("Departures", "fresh fix ok in ${elapsed}ms (provider=$provider)")
        fix
    } catch (t: Throwable) {
        Log.d("Departures", "fresh fix failed after ${SystemClock.elapsedRealtime() - startedAt}ms: $t")
        throw t
    } finally {
        signal.cancel()
    }
}

// getCurrentLocation needs an Executor; run the callback inline on the delivering thread.
private val DIRECT_EXECUTOR = java.util.concurrent.Executor { it.run() }

private fun Location.ageMs(): Long =
    (SystemClock.elapsedRealtimeNanos() - elapsedRealtimeNanos) / 1_000_000

private fun Location.isUsable(): Boolean =
    ageMs() <= MAX_LAST_LOCATION_AGE_MS && (accuracy <= 0f || accuracy <= MAX_ACCURACY_METERS)
