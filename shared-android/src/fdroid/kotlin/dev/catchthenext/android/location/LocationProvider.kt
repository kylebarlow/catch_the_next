package dev.catchthenext.android.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.os.SystemClock
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
 * class — call sites in `main` are unchanged.
 */
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

    private fun lastKnownUsable(): LatLon? = runCatching {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        manager?.bestLastKnownLocation()
            ?.takeIf { it.isUsable() }
            ?.let { LatLon(it.latitude, it.longitude) }
    }.getOrNull()

    suspend fun locate(mode: LocationMode): LatLon? = withContext(Dispatchers.IO) {
        val hasPerm = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPerm) return@withContext null

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
                    else manager.fetchFresh(timeoutMs = Tuning.PASSIVE_LOCATION_TIMEOUT_MS)
                }
                LocationMode.HIGH -> {
                    manager.fetchFresh(timeoutMs = 20_000L)
                        ?: manager.bestLastKnownLocation()
                            ?.takeIf { it.isUsable() }
                            ?.let { LatLon(it.latitude, it.longitude) }
                }
            }
        }.getOrNull()
        result?.also { LocationCache.put(it) }
        result
    }
}

private fun LocationManager.bestLastKnownLocation(): Location? {
    val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
    return providers.mapNotNull { p ->
        runCatching { if (isProviderEnabled(p)) getLastKnownLocation(p) else null }.getOrNull()
    }.maxByOrNull { it.time }
}

private suspend fun LocationManager.fetchFresh(timeoutMs: Long): LatLon? {
    val provider = when {
        isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
        isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
        else -> return null
    }
    val signal = CancellationSignal()
    return try {
        withTimeoutOrNull(timeoutMs) {
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
    } finally {
        signal.cancel()
    }
}

// getCurrentLocation needs an Executor; run the callback inline on the delivering thread.
private val DIRECT_EXECUTOR = java.util.concurrent.Executor { it.run() }

private fun Location.isUsable(): Boolean {
    val ageMs = (SystemClock.elapsedRealtimeNanos() - elapsedRealtimeNanos) / 1_000_000
    return ageMs <= MAX_LAST_LOCATION_AGE_MS && (accuracy <= 0f || accuracy <= MAX_ACCURACY_METERS)
}
