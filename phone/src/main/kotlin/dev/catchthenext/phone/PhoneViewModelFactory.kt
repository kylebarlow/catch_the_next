package dev.catchthenext.phone

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import dev.catchthenext.android.location.LatLon
import dev.catchthenext.android.location.LocationProvider
import dev.catchthenext.android.location.asHighAccuracy
import dev.catchthenext.android.storage.AttributionStore
import dev.catchthenext.android.storage.DistanceUnitStore
import dev.catchthenext.android.tile.StopWithDepartures
import dev.catchthenext.android.tile.TileDataStore
import dev.catchthenext.android.tile.TileState
import dev.catchthenext.android.tile.computeTileState
import dev.catchthenext.android.tile.makeFetchNetworkDeparturesBatch
import dev.catchthenext.android.tile.resolveStaleStops
import dev.catchthenext.android.location.LocationCache
import dev.catchthenext.android.ui.AboutViewModel
import dev.catchthenext.android.ui.AddStopViewModel
import dev.catchthenext.android.ui.DeparturesViewModel
import dev.catchthenext.android.ui.FavoritesViewModel
import dev.catchthenext.android.ui.PlaceSearchViewModel
import dev.catchthenext.android.ui.SettingsViewModel
import dev.catchthenext.android.sync.FavoritesSyncPusher
import dev.catchthenext.android.sync.SyncMetadataStore
import kotlinx.coroutines.flow.first

class PhoneViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T = when {
        modelClass.isAssignableFrom(DeparturesViewModel::class.java) -> {
            val client = PhoneGraph.transitlandClient()
            val dataStore = TileDataStore(context)
            val locationProvider = LocationProvider(context)
            val favoritesManager = PhoneGraph.favoritesManager(context)
            val distanceStore = DistanceUnitStore(context)
            DeparturesViewModel(
                quickCacheRead = {
                    val now = System.currentTimeMillis()
                    val hasPerm = ContextCompat.checkSelfPermission(
                        context, Manifest.permission.ACCESS_COARSE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
                    if (!hasPerm) {
                        TileState.NoPermission
                    } else {
                        val favorites = favoritesManager.getFavorites()
                        if (favorites.isEmpty()) {
                            TileState.NoFavorites
                        } else {
                            val cache = dataStore.read()
                            val freshStops = cache.nearbyDepartures.filter { now - it.fetchedAt < 60_000L }
                            val stops = freshStops.mapNotNull { cached ->
                                val stop = favorites.firstOrNull { it.id == cached.stopId }
                                if (stop == null) null
                                else StopWithDepartures(
                                    stop = stop,
                                    distanceMeters = 0.0,
                                    departures = cached.departures.filter { it.currentMinutes() >= 0 },
                                    fetchedAt = cached.fetchedAt,
                                    alerts = cached.alerts ?: emptyList(),
                                )
                            }
                            if (stops.isEmpty()) null
                            else TileState.Ready(stops, stops.minOf { it.fetchedAt })
                        }
                    }
                },
                computeState = { forceFresh ->
                    var favorites = favoritesManager.getFavorites()
                    val hasPerm = ContextCompat.checkSelfPermission(
                        context, Manifest.permission.ACCESS_COARSE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
                    val freshLocation = if (hasPerm) locationProvider.currentLocation() else null
                    val cache = dataStore.read()
                    if (freshLocation != null) dataStore.updateLocation(freshLocation.lat, freshLocation.lon)
                    val lat = freshLocation?.lat ?: cache.lat
                    val lon = freshLocation?.lon ?: cache.lon
                    val location = if (lat != null && lon != null) LatLon(lat, lon) else null
                    val threshold = distanceStore.thresholdMetersFlow.first()
                    var state = computeTileState(
                        favorites = favorites,
                        location = location,
                        hasPermission = hasPerm,
                        thresholdMeters = threshold,
                        fetchDeparturesBatch = makeFetchNetworkDeparturesBatch(
                            getDeparturesBatch = { ids -> client.getDeparturesBatch(ids) },
                            cache = cache,
                            stops = favorites,
                            forceFresh = forceFresh,
                        ),
                        persistDepartures = { stops -> dataStore.updateNearbyDepartures(stops) },
                    )
                    if (state is TileState.Ready) {
                        val resolved = resolveStaleStops(state, favorites) { lat, lon ->
                            client.getNearbyStops(lat, lon, radiusMeters = 100)
                        }
                        if (resolved != null) {
                            favoritesManager.saveFavorites(resolved)
                            favorites = resolved
                            state = computeTileState(
                                favorites = favorites,
                                location = location,
                                hasPermission = hasPerm,
                                thresholdMeters = threshold,
                                fetchDeparturesBatch = makeFetchNetworkDeparturesBatch(
                                    getDeparturesBatch = { ids -> client.getDeparturesBatch(ids) },
                                    cache = dataStore.read(),
                                    stops = favorites,
                                    forceFresh = true,
                                ),
                                persistDepartures = { stops -> dataStore.updateNearbyDepartures(stops) },
                            )
                        }
                    }
                    state
                }
            ) as T
        }
        modelClass.isAssignableFrom(FavoritesViewModel::class.java) -> {
            val mgr = PhoneGraph.favoritesManager(context)
            val locationProvider = LocationProvider(context)
            val store = DistanceUnitStore(context)
            val dataStore = TileDataStore(context)
            FavoritesViewModel(
                favoritesFlow = mgr.favoritesFlow(),
                favoritesManager = mgr,
                locationProvider = locationProvider,
                highAccuracyLocate = locationProvider.asHighAccuracy(),
                distanceUnitFlow = store.unitFlow,
                persistUnit = { store.setUnit(it) },
                readAlertsByStopId = {
                    dataStore.read().nearbyDepartures
                        .associate { it.stopId to (it.alerts?.isNotEmpty() == true) }
                },
            ) as T
        }
        modelClass.isAssignableFrom(AddStopViewModel::class.java) ->
            AddStopViewModel(
                getNearbyStops = { lat, lon, r -> PhoneGraph.transitlandClient().getNearbyStops(lat, lon, r) },
                favoritesManager = PhoneGraph.favoritesManager(context),
                locationProvider = LocationProvider(context),
            ) as T
        modelClass.isAssignableFrom(PlaceSearchViewModel::class.java) ->
            PlaceSearchViewModel(
                geocode = { q, focusLat, focusLon ->
                    PhoneGraph.transitlandClient().geocodePlace(q, focusLat, focusLon)
                },
                focus = { LocationCache.get() },
            ) as T
        modelClass.isAssignableFrom(SettingsViewModel::class.java) -> {
            val store = DistanceUnitStore(context)
            val fm = PhoneGraph.favoritesManager(context)
            val meta = SyncMetadataStore(context)
            SettingsViewModel(
                distanceUnitFlow = store.unitFlow,
                persistUnit = { store.setUnit(it) },
                thresholdMetersFlow = store.thresholdMetersFlow,
                persistThreshold = { store.setThresholdMeters(it) },
                peerLabel = "watch",
                pushToPeer = { FavoritesSyncPusher.pushToPeer(context, fm, meta) },
            ) as T
        }
        modelClass.isAssignableFrom(AboutViewModel::class.java) -> {
            val attrStore = AttributionStore(context)
            AboutViewModel(
                attributionsFlow = attrStore.attributionsFlow,
                unattributedFeedsFlow = attrStore.unattributedFeedsFlow,
            ) as T
        }
        else -> throw IllegalArgumentException("Unknown ViewModel: $modelClass")
    }
}
