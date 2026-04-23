package dev.catchthenext.wear.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

fun interface CurrentLocationProvider {
    suspend fun currentLocation(): LatLon?
}

class LocationProvider(private val context: Context) : CurrentLocationProvider {
    override suspend fun currentLocation(): LatLon? = withContext(Dispatchers.IO) {
        val hasPerm = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPerm) return@withContext null
        val client = LocationServices.getFusedLocationProviderClient(context)
        runCatching {
            // Use last known location — returns instantly from cache.
            // Only fall back to getCurrentLocation on cold start (null last location).
            val last = client.lastLocation.await()
            if (last != null) return@runCatching LatLon(last.latitude, last.longitude)
            val cts = CancellationTokenSource()
            val loc = client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cts.token).await()
            loc?.let { LatLon(it.latitude, it.longitude) }
        }.getOrNull()
    }
}
