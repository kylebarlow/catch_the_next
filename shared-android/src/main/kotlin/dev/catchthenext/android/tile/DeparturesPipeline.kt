package dev.catchthenext.android.tile

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import dev.catchthenext.android.location.LatLon
import dev.catchthenext.android.location.LocationProvider
import dev.catchthenext.android.storage.DistanceUnitStore
import dev.catchthenext.api.StopDepartures
import dev.catchthenext.api.TransitApi
import dev.catchthenext.model.Stop
import dev.catchthenext.storage.FavoritesManager
import kotlinx.coroutines.flow.first

/**
 * The shared departures fetch pipeline used by the phone and wear `DeparturesViewModel`s and the
 * wear tile. Collaborators are injected as lambdas so the logic is testable without Android deps;
 * [departuresPipeline] wires the Android implementations.
 *
 * [computeState] performs stale-stop resolution: every consumer (including the tile) re-resolves
 * favorites when Transitland rotates a stop's integer ID.
 */
class DeparturesPipeline(
    private val getDeparturesBatch: suspend (List<String>) -> Map<String, StopDepartures>,
    private val getNearbyStops: suspend (Double, Double) -> List<Stop>,
    private val getFavorites: suspend () -> List<Stop>,
    private val saveFavorites: suspend (List<Stop>) -> Unit,
    private val readCache: suspend () -> CachedTileData,
    private val persistDepartures: suspend (List<StopWithDepartures>) -> Unit,
    private val updateCachedLocation: suspend (Double, Double) -> Unit,
    private val hasLocationPermission: () -> Boolean,
    private val currentLocation: suspend () -> LatLon?,  // only invoked when permission granted
    private val thresholdMeters: suspend () -> Int,
    private val now: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) {
    /** Fast cached read for the initial UI frame: only fresh (<TTL) cached stops, else null. */
    suspend fun quickCacheRead(): TileState? {
        val now = now()
        if (!hasLocationPermission()) return TileState.NoPermission
        val favorites = getFavorites()
        if (favorites.isEmpty()) return TileState.NoFavorites
        val cache = readCache()
        val freshStops = cache.nearbyDepartures.filter { now - it.fetchedAt < Tuning.CACHE_TTL_MS }
        val stops = freshStops.mapNotNull { cached ->
            val stop = favorites.firstOrNull { it.id == cached.stopId } ?: return@mapNotNull null
            StopWithDepartures(
                stop = stop,
                distanceMeters = 0.0,
                departures = cached.departures.filter { it.currentMinutes() >= 0 },
                fetchedAt = cached.fetchedAt,
                alerts = cached.alerts ?: emptyList(),
            )
        }
        return if (stops.isEmpty()) null
        else TileState.Ready(stops, stops.minOf { it.fetchedAt })
    }

    suspend fun computeState(forceFresh: Boolean): TileState {
        var favorites = getFavorites()
        val hasPerm = hasLocationPermission()
        val freshLocation = if (hasPerm) currentLocation() else null
        val cache = readCache()
        if (freshLocation != null) updateCachedLocation(freshLocation.lat, freshLocation.lon)
        val lat = freshLocation?.lat ?: cache.lat
        val lon = freshLocation?.lon ?: cache.lon
        val location = if (lat != null && lon != null) LatLon(lat, lon) else null
        val threshold = thresholdMeters()
        log("computeState force=$forceFresh favorites=${favorites.size} hasPerm=$hasPerm loc=${location != null} threshold=$threshold")
        var state = computeTileState(
            favorites = favorites,
            location = location,
            hasPermission = hasPerm,
            thresholdMeters = threshold,
            fetchDeparturesBatch = makeFetchNetworkDeparturesBatch(
                getDeparturesBatch = getDeparturesBatch,
                cache = cache,
                stops = favorites,
                forceFresh = forceFresh,
            ),
            persistDepartures = persistDepartures,
        )
        if (state is TileState.Ready) {
            val resolved = resolveStaleStops(state, favorites, getNearbyStops)
            if (resolved != null) {
                saveFavorites(resolved)
                favorites = resolved
                state = computeTileState(
                    favorites = favorites,
                    location = location,
                    hasPermission = hasPerm,
                    thresholdMeters = threshold,
                    fetchDeparturesBatch = makeFetchNetworkDeparturesBatch(
                        getDeparturesBatch = getDeparturesBatch,
                        cache = readCache(),
                        stops = favorites,
                        forceFresh = true,
                    ),
                    persistDepartures = persistDepartures,
                )
            }
        }
        log("computeState result=${state::class.simpleName} stops=${(state as? TileState.Ready)?.stops?.size ?: 0}")
        return state
    }
}

/** Wires the Android implementations of the [DeparturesPipeline] collaborators. */
fun departuresPipeline(
    context: Context,
    client: TransitApi,
    favoritesManager: FavoritesManager,
    dataStore: TileDataStore = TileDataStore(context),
): DeparturesPipeline {
    val appContext = context.applicationContext
    val locationProvider = LocationProvider(appContext)
    val distanceStore = DistanceUnitStore(appContext)
    return DeparturesPipeline(
        getDeparturesBatch = { ids -> client.getDeparturesBatch(ids) },
        getNearbyStops = { lat, lon -> client.getNearbyStops(lat, lon, radiusMeters = Tuning.STALE_RESOLVE_RADIUS_M) },
        getFavorites = { favoritesManager.getFavorites() },
        saveFavorites = { stops -> favoritesManager.saveFavorites(stops) },
        readCache = { dataStore.read() },
        persistDepartures = { stops -> dataStore.updateNearbyDepartures(stops) },
        updateCachedLocation = { lat, lon -> dataStore.updateLocation(lat, lon) },
        hasLocationPermission = {
            ContextCompat.checkSelfPermission(
                appContext, Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        },
        currentLocation = { locationProvider.currentLocation() },
        thresholdMeters = { distanceStore.thresholdMetersFlow.first() },
        log = { Log.d("Departures", it) },
    )
}
