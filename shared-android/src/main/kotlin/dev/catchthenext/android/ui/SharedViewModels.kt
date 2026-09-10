package dev.catchthenext.android.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import dev.catchthenext.android.location.LocationProvider
import dev.catchthenext.android.location.asHighAccuracy
import dev.catchthenext.android.storage.AttributionStore
import dev.catchthenext.android.storage.DistanceUnitStore
import dev.catchthenext.android.storage.SyncedFavoritesManager
import dev.catchthenext.android.sync.FavoritesSyncController
import dev.catchthenext.android.sync.ReachabilityState
import dev.catchthenext.android.sync.SyncStateStore
import dev.catchthenext.android.tile.TileDataStore
import dev.catchthenext.android.tile.departuresPipeline
import dev.catchthenext.api.TransitApi
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Collaborators shared by the phone and wear ViewModel factories. Only [peerLabel] (and the
 * underlying graph that supplied the others) differs between platforms.
 */
class SharedViewModelDeps(
    val context: Context,
    val client: TransitApi,
    val favoritesManager: SyncedFavoritesManager,
    val syncStateStore: SyncStateStore,
    val favoritesSyncController: FavoritesSyncController,
    val peerLabel: String,  // "watch" on phone, "phone" on wear
    /** Wear passes true so a watch with no phone/WiFi nearby can still fall back to GPS. */
    val gpsFallback: Boolean = false,
)

/**
 * Builds the ViewModels duplicated across phone and wear. Returns null for classes the caller
 * must handle itself (phone-only place search, the wear-only stop detail/alert/confirm models).
 */
fun createSharedViewModel(modelClass: Class<*>, deps: SharedViewModelDeps): ViewModel? = when {
    modelClass.isAssignableFrom(DeparturesViewModel::class.java) -> {
        val pipeline = departuresPipeline(
            deps.context, deps.client, deps.favoritesManager, gpsFallback = deps.gpsFallback,
        )
        DeparturesViewModel(
            favoritesCountFlow = deps.favoritesManager.favoritesFlow().map { it.size }.distinctUntilChanged(),
            quickCacheRead = pipeline::quickCacheRead,
            computeState = { force, onIntermediate -> pipeline.computeState(force, onIntermediate) },
        )
    }
    modelClass.isAssignableFrom(FavoritesViewModel::class.java) -> {
        val locationProvider = LocationProvider(deps.context, deps.gpsFallback)
        val store = DistanceUnitStore(deps.context)
        val dataStore = TileDataStore(deps.context)
        FavoritesViewModel(
            favoritesFlow = deps.favoritesManager.favoritesFlow(),
            favoritesManager = deps.favoritesManager,
            locationProvider = locationProvider,
            highAccuracyLocate = locationProvider.asHighAccuracy(),
            distanceUnitFlow = store.unitFlow,
            persistUnit = { store.setUnit(it) },
            readAlertsByStopId = {
                dataStore.read().nearbyDepartures
                    .associate { it.stopId to it.alerts.orEmpty() }
            },
        )
    }
    modelClass.isAssignableFrom(AddStopViewModel::class.java) ->
        AddStopViewModel(
            getNearbyStops = { lat, lon, r -> deps.client.getNearbyStops(lat, lon, r) },
            favoritesManager = deps.favoritesManager,
            locationProvider = LocationProvider(deps.context),
        )
    modelClass.isAssignableFrom(SettingsViewModel::class.java) -> {
        val store = DistanceUnitStore(deps.context)
        SettingsViewModel(
            distanceUnitFlow = store.unitFlow,
            persistUnit = { store.setUnit(it) },
            thresholdMetersFlow = store.thresholdMetersFlow,
            persistThreshold = { store.setThresholdMeters(it) },
            peerLabel = deps.peerLabel,
            doSync = { deps.favoritesSyncController.reconcile(deps.context, deps.syncStateStore) },
            peerReachableFlow = ReachabilityState.reachable,
        )
    }
    modelClass.isAssignableFrom(AboutViewModel::class.java) -> {
        val attrStore = AttributionStore(deps.context)
        AboutViewModel(
            attributionsFlow = attrStore.attributionsFlow,
            unattributedFeedsFlow = attrStore.unattributedFeedsFlow,
        )
    }
    else -> null
}
