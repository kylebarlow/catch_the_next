package dev.catchthenext.android.tile

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import dev.catchthenext.android.location.LatLon
import dev.catchthenext.android.location.LocationFix
import dev.catchthenext.android.location.LocationProvider
import dev.catchthenext.android.storage.DistanceUnitStore
import dev.catchthenext.api.StopDepartures
import dev.catchthenext.api.TransitApi
import dev.catchthenext.model.Stop
import dev.catchthenext.storage.FavoritesManager
import dev.catchthenext.android.location.QuickLocationProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
    private val updateCachedLocation: suspend (Double, Double, Long) -> Unit,
    private val hasLocationPermission: () -> Boolean,
    private val currentLocation: suspend () -> LatLon?,  // only invoked when permission granted
    private val thresholdMeters: suspend () -> Int,
    /** Fast, never-blocking location used to start the fetch; see [QuickLocationProvider]. */
    private val quickLocation: suspend () -> LocationFix? = { null },
    private val now: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) {
    /**
     * Fast cached read for the initial UI frame. Reaches back [Tuning.QUICK_CACHE_MAX_AGE_MS] so
     * the user sees their last known departures instead of a spinner, marking anything older than
     * [Tuning.CACHE_TTL_MS] as `isStale`. Returns null when nothing usable (a cached stop that is
     * still a favorite, with at least one departure still in the future) remains.
     */
    suspend fun quickCacheRead(): TileState? {
        val now = now()
        if (!hasLocationPermission()) return TileState.NoPermission
        val favorites = getFavorites()
        if (favorites.isEmpty()) return TileState.NoFavorites
        val cache = readCache()
        val usable = cache.nearbyDepartures.filter { now - it.fetchedAt < Tuning.QUICK_CACHE_MAX_AGE_MS }
        val stops = usable.mapNotNull { cached ->
            val stop = favorites.firstOrNull { it.id == cached.stopId } ?: return@mapNotNull null
            val departures = cached.departures.filter { it.currentMinutes() >= 0 }
            if (departures.isEmpty()) return@mapNotNull null
            StopWithDepartures(
                stop = stop,
                distanceMeters = 0.0,
                departures = departures,
                fetchedAt = cached.fetchedAt,
                alerts = cached.alerts ?: emptyList(),
                isStale = now - cached.fetchedAt >= Tuning.CACHE_TTL_MS,
            )
        }
        return if (stops.isEmpty()) null
        else TileState.Ready(stops, stops.minOf { it.fetchedAt })
    }

    /**
     * Fetches departures for the nearby favorites. The network call goes out against the best
     * location already available (the newer of [quickLocation] and the persisted one) while a
     * fresh fix is resolved in parallel; the fetch is only redone if the fresh fix picks
     * different stops.
     *
     * [onIntermediate] is invoked once with the first result, before the fresh fix is awaited, so
     * a caller can paint immediately and re-render only if the refine pass changes anything. It
     * is not called when there is no start location (that path has nothing to show yet) nor when
     * no refine pass is pending.
     */
    suspend fun computeState(
        forceFresh: Boolean,
        onIntermediate: suspend (TileState) -> Unit = {},
    ): TileState = coroutineScope {
        var favorites = getFavorites()
        val hasPerm = hasLocationPermission()
        val cache = readCache()
        val threshold = thresholdMeters()

        val quick = if (hasPerm) quickLocation() else null
        val persisted = cache.lat?.let { lat ->
            cache.lon?.let { lon -> LocationFix(LatLon(lat, lon), cache.locationAt ?: 0L) }
        }
        // Newest wins: a 10-minute-old last-known fix beats a position persisted hours ago, and a
        // pre-upgrade persisted position (no timestamp) loses to any quick fix.
        val start = listOfNotNull(quick, persisted).maxByOrNull { it.atMillis }
        val startLoc = start?.loc
        val source = when {
            start == null -> "none"
            start === quick -> "quick"
            else -> "persisted"
        }
        val startedAt = now()

        // Nothing to start from — fall back to waiting for a fix, as before.
        val freshDeferred = if (hasPerm && startLoc != null) async { currentLocation() } else null
        val location = startLoc ?: (if (hasPerm) currentLocation() else null)?.also {
            updateCachedLocation(it.lat, it.lon, now())
        }

        val age = start?.let { "${(startedAt - it.atMillis) / 1000}s" } ?: "unknown"
        log("computeState force=$forceFresh favorites=${favorites.size} hasPerm=$hasPerm loc=${location != null} locSource=$source age=$age threshold=$threshold")

        var state = fetchFor(favorites, location, hasPerm, threshold, cache, forceFresh)
        val intermediate = state
        if (freshDeferred != null) onIntermediate(state)

        // Refine: if the fresh fix would have selected different stops, redo the fetch for it.
        val fresh = freshDeferred?.await()
        if (freshDeferred != null) {
            val waited = now() - startedAt
            log(if (fresh != null) "fresh fix ok in ${waited}ms" else "fresh fix none after ${waited}ms")
        }
        if (fresh != null) {
            updateCachedLocation(fresh.lat, fresh.lon, now())
            val before = location?.let { selectStops(favorites, it.lat, it.lon, threshold).map { p -> p.first.id } }
            val after = selectStops(favorites, fresh.lat, fresh.lon, threshold).map { it.first.id }
            if (before != after) {
                log("computeState refetching for fresh location (stops $before -> $after)")
                state = fetchFor(favorites, fresh, hasPerm, threshold, readCache(), forceFresh)
            }
        }

        if (state is TileState.Ready) {
            val resolved = resolveStaleStops(state, favorites, getNearbyStops)
            if (resolved != null) {
                saveFavorites(resolved)
                favorites = resolved
                state = fetchFor(favorites, fresh ?: location, hasPerm, threshold, readCache(), forceFresh = true)
            }
        }
        log("computeState result=${state::class.simpleName} stops=${(state as? TileState.Ready)?.stops?.size ?: 0} changed=${state !== intermediate}")
        state
    }

    private suspend fun fetchFor(
        favorites: List<Stop>,
        location: LatLon?,
        hasPerm: Boolean,
        threshold: Int,
        cache: CachedTileData,
        forceFresh: Boolean,
    ): TileState = computeTileState(
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
}

/** Wires the Android implementations of the [DeparturesPipeline] collaborators. */
fun departuresPipeline(
    context: Context,
    client: TransitApi,
    favoritesManager: FavoritesManager,
    dataStore: TileDataStore = TileDataStore(context),
    /** Wear passes true: see [LocationProvider]'s rate-limited high-accuracy fallback. */
    gpsFallback: Boolean = false,
): DeparturesPipeline {
    val appContext = context.applicationContext
    val locationProvider = LocationProvider(appContext, gpsFallback)
    val distanceStore = DistanceUnitStore(appContext)
    return DeparturesPipeline(
        getDeparturesBatch = { ids -> client.getDeparturesBatch(ids) },
        getNearbyStops = { lat, lon -> client.getNearbyStops(lat, lon, radiusMeters = Tuning.STALE_RESOLVE_RADIUS_M) },
        getFavorites = { favoritesManager.getFavorites() },
        saveFavorites = { stops -> favoritesManager.saveFavorites(stops) },
        readCache = { dataStore.read() },
        persistDepartures = { stops -> dataStore.updateNearbyDepartures(stops) },
        updateCachedLocation = { lat, lon, at -> dataStore.updateLocation(lat, lon, at) },
        hasLocationPermission = {
            ContextCompat.checkSelfPermission(
                appContext, Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        },
        currentLocation = { locationProvider.currentLocation() },
        thresholdMeters = { distanceStore.thresholdMetersFlow.first() },
        quickLocation = { locationProvider.quickLocation() },
        log = { Log.d("Departures", it) },
    )
}
