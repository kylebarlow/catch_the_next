package dev.catchthenext.android.location

/**
 * Pure, flavor-agnostic location declarations. The concrete [LocationProvider] class and the
 * [locationUpdates] flow are twinned per product flavor (GMS FusedLocation in `play`, AOSP
 * `LocationManager` in `fdroid`) — but their public API is identical, so call sites in `main`
 * (and the [asHighAccuracy] extension below) depend only on these declarations.
 */
enum class LocationMode { PASSIVE, HIGH }

fun interface CurrentLocationProvider {
    suspend fun currentLocation(): LatLon?
}

/**
 * Best-effort location that must return fast: the in-memory cache or a usable last-known fix,
 * never a fresh fix. Lets a caller start its network work immediately and refine afterwards.
 */
fun interface QuickLocationProvider {
    suspend fun quickLocation(): LatLon?
}

/** Returns a [CurrentLocationProvider] that always forces a HIGH-accuracy fix. */
fun LocationProvider.asHighAccuracy(): CurrentLocationProvider =
    CurrentLocationProvider { locate(LocationMode.HIGH) }
