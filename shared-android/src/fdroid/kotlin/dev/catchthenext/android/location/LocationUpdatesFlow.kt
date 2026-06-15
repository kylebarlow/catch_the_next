package dev.catchthenext.android.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * AOSP (FOSS) twin of the play [locationUpdates]. Uses the framework [LocationManager] rather
 * than FusedLocation. Identical signature so call sites in `main`/phone are unchanged.
 */
fun locationUpdates(ctx: Context, intervalMs: Long = 10_000L): Flow<LatLon> = callbackFlow {
    val hasPerm = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
    if (!hasPerm) {
        close()
        return@callbackFlow
    }

    val manager = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    if (manager == null) {
        close()
        return@callbackFlow
    }

    val provider = when {
        manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
        manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
        else -> {
            close()
            return@callbackFlow
        }
    }

    val listener = LocationListener { loc: Location ->
        val latLon = LatLon(loc.latitude, loc.longitude)
        LocationCache.put(latLon)
        trySend(latLon)
    }

    manager.requestLocationUpdates(provider, intervalMs, 0f, listener, ctx.mainLooper ?: Looper.getMainLooper())
    awaitClose { manager.removeUpdates(listener) }
}
