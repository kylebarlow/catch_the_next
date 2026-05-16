package dev.catchthenext.android.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

fun locationUpdates(ctx: Context, intervalMs: Long = 10_000L): Flow<LatLon> = callbackFlow {
    val hasPerm = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
    if (!hasPerm) {
        close()
        return@callbackFlow
    }

    val client = LocationServices.getFusedLocationProviderClient(ctx)
    val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, intervalMs)
        .setMinUpdateIntervalMillis(intervalMs / 2)
        .build()

    val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            val latLon = LatLon(loc.latitude, loc.longitude)
            LocationCache.put(latLon)
            trySend(latLon)
        }
    }

    client.requestLocationUpdates(request, callback, ctx.mainLooper)
    awaitClose { client.removeLocationUpdates(callback) }
}
