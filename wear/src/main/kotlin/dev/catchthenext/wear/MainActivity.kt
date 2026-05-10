package dev.catchthenext.wear

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
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
import dev.catchthenext.android.ui.AboutViewModel
import dev.catchthenext.android.ui.AddStopViewModel
import dev.catchthenext.android.ui.DeparturesViewModel
import dev.catchthenext.android.ui.FavoritesViewModel
import dev.catchthenext.android.ui.SettingsViewModel
import dev.catchthenext.android.ui.StopConfirmViewModel
import dev.catchthenext.android.ui.StopDetailsViewModel
import dev.catchthenext.model.Stop
import dev.catchthenext.wear.ui.AboutScreen
import dev.catchthenext.wear.ui.AddStopScreen
import dev.catchthenext.wear.ui.DeparturesScreen
import dev.catchthenext.wear.ui.FavoritesScreen
import dev.catchthenext.wear.ui.SettingsScreen
import dev.catchthenext.wear.ui.SettingsThresholdScreen
import dev.catchthenext.wear.ui.StopConfirmScreen
import dev.catchthenext.wear.ui.StopDetailsScreen
import dev.catchthenext.android.sync.FavoritesSyncListener
import dev.catchthenext.android.sync.ReachabilityState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* permissions granted or denied — DeparturesViewModel re-checks on next state refresh */ }

    override fun onResume() {
        super.onResume()
        CoroutineScope(Dispatchers.IO).launch {
            FavoritesSyncListener.coldStartReconcile(
                applicationContext,
                WearGraph.syncStateStore(applicationContext),
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val missing = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ).filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) locationPermissionLauncher.launch(missing.toTypedArray())
        setContent {
            val factory = WearViewModelFactory(applicationContext)
            MaterialTheme {
                val navController = rememberSwipeDismissableNavController()
                WearNavGraph(navController, factory)
            }
        }
    }
}

@Composable
private fun WearNavGraph(navController: NavHostController, factory: WearViewModelFactory) {
    SwipeDismissableNavHost(navController = navController, startDestination = "departures") {
        composable("departures") {
            val vm: DeparturesViewModel = viewModel(factory = factory)
            DeparturesScreen(navController = navController, viewModel = vm)
        }
        composable("favorites") {
            val vm: FavoritesViewModel = viewModel(factory = factory)
            FavoritesScreen(navController = navController, viewModel = vm)
        }
        composable("add") {
            val vm: AddStopViewModel = viewModel(factory = factory)
            AddStopScreen(navController = navController, viewModel = vm)
        }
        composable("details/{stopId}") { backStackEntry ->
            val stopId = backStackEntry.arguments?.getString("stopId")?.toLongOrNull() ?: 0L
            val detailsFactory = StopDetailsViewModelFactory(navController.context, stopId)
            val vm: StopDetailsViewModel = viewModel(factory = detailsFactory)
            StopDetailsScreen(navController = navController, viewModel = vm)
        }
        composable("confirm") {
            val stop = WearGraph.pendingConfirmStop
            if (stop == null) {
                navController.popBackStack()
            } else {
                val confirmFactory = StopConfirmViewModelFactory(navController.context, stop)
                val vm: StopConfirmViewModel = viewModel(factory = confirmFactory)
                StopConfirmScreen(navController = navController, viewModel = vm)
            }
        }
        composable("settings") {
            val vm: SettingsViewModel = viewModel(factory = factory)
            SettingsScreen(navController = navController, viewModel = vm)
        }
        composable("settings/threshold") {
            val vm: SettingsViewModel = viewModel(factory = factory)
            SettingsThresholdScreen(navController = navController, viewModel = vm)
        }
        composable("about") {
            val vm: AboutViewModel = viewModel(factory = factory)
            AboutScreen(viewModel = vm)
        }
    }
}

class WearViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T = when {
        modelClass.isAssignableFrom(DeparturesViewModel::class.java) -> {
            val client = WearGraph.transitlandClient()
            val dataStore = TileDataStore(context)
            val locationProvider = LocationProvider(context)
            val favoritesManager = WearGraph.favoritesManager(context)
            val distanceStore = DistanceUnitStore(context)
            DeparturesViewModel(
                favoritesCountFlow = favoritesManager.favoritesFlow().map { it.size }.distinctUntilChanged(),
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
                    Log.d("Departures", "computeState force=$forceFresh favorites=${favorites.size} hasPerm=$hasPerm loc=${location != null} threshold=$threshold")
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
                    Log.d("Departures", "computeState result=${state::class.simpleName} stops=${(state as? TileState.Ready)?.stops?.size ?: 0}")
                    state
                }
            ) as T
        }
        modelClass.isAssignableFrom(FavoritesViewModel::class.java) -> {
            val mgr = WearGraph.favoritesManager(context)
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
                getNearbyStops = { lat, lon, r -> WearGraph.transitlandClient().getNearbyStops(lat, lon, r) },
                favoritesManager = WearGraph.favoritesManager(context),
                locationProvider = LocationProvider(context),
            ) as T
        modelClass.isAssignableFrom(SettingsViewModel::class.java) -> {
            val store = DistanceUnitStore(context)
            val syncStore = WearGraph.syncStateStore(context)
            SettingsViewModel(
                distanceUnitFlow = store.unitFlow,
                persistUnit = { store.setUnit(it) },
                thresholdMetersFlow = store.thresholdMetersFlow,
                persistThreshold = { store.setThresholdMeters(it) },
                peerLabel = "phone",
                doSync = { FavoritesSyncListener.coldStartReconcile(context, syncStore) },
                peerReachableFlow = ReachabilityState.reachable,
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

class StopDetailsViewModelFactory(
    private val context: Context,
    private val stopId: Long,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        StopDetailsViewModel(
            getDepartures = { id -> WearGraph.transitlandClient().getDepartures(id) },
            favoritesManager = WearGraph.favoritesManager(context),
            stopId = stopId,
        ) as T
}

class StopConfirmViewModelFactory(
    private val context: Context,
    private val stop: Stop,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        StopConfirmViewModel(
            getDepartures = { id -> WearGraph.transitlandClient().getDepartures(id).departures },
            favoritesManager = WearGraph.favoritesManager(context),
            stop = stop,
        ) as T
}
