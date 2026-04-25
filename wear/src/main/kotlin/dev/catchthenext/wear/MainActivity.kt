package dev.catchthenext.wear

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import dev.catchthenext.model.Stop
import dev.catchthenext.wear.location.LocationProvider
import dev.catchthenext.wear.location.asHighAccuracy
import dev.catchthenext.wear.storage.AttributionStore
import dev.catchthenext.wear.storage.DistanceUnitStore
import dev.catchthenext.wear.ui.AboutScreen
import dev.catchthenext.wear.ui.AboutViewModel
import dev.catchthenext.wear.ui.AddStopScreen
import dev.catchthenext.wear.ui.AddStopViewModel
import dev.catchthenext.wear.ui.FavoritesScreen
import dev.catchthenext.wear.ui.FavoritesViewModel
import dev.catchthenext.wear.ui.SettingsScreen
import dev.catchthenext.wear.ui.SettingsThresholdScreen
import dev.catchthenext.wear.ui.SettingsViewModel
import dev.catchthenext.wear.ui.StopConfirmScreen
import dev.catchthenext.wear.ui.StopConfirmViewModel
import dev.catchthenext.wear.ui.StopDetailsScreen
import dev.catchthenext.wear.ui.StopDetailsViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
    SwipeDismissableNavHost(navController = navController, startDestination = "favorites") {
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
        modelClass.isAssignableFrom(FavoritesViewModel::class.java) -> {
            val mgr = WearGraph.favoritesManager(context)
            val locationProvider = LocationProvider(context)
            val store = DistanceUnitStore(context)
            FavoritesViewModel(
                favoritesFlow = mgr.favoritesFlow(),
                favoritesManager = mgr,
                locationProvider = locationProvider,
                highAccuracyLocate = locationProvider.asHighAccuracy(),
                distanceUnitFlow = store.unitFlow,
                persistUnit = { store.setUnit(it) },
            ) as T
        }
        modelClass.isAssignableFrom(AddStopViewModel::class.java) ->
            AddStopViewModel(
                getNearbyStops = { lat, lon -> WearGraph.transitlandClient().getNearbyStops(lat, lon) },
                favoritesManager = WearGraph.favoritesManager(context),
                locationProvider = LocationProvider(context),
            ) as T
        modelClass.isAssignableFrom(SettingsViewModel::class.java) -> {
            val store = DistanceUnitStore(context)
            SettingsViewModel(
                distanceUnitFlow = store.unitFlow,
                persistUnit = { store.setUnit(it) },
                thresholdMetersFlow = store.thresholdMetersFlow,
                persistThreshold = { store.setThresholdMeters(it) },
            ) as T
        }
        modelClass.isAssignableFrom(AboutViewModel::class.java) ->
            AboutViewModel(
                attributionsFlow = AttributionStore(context).attributionsFlow,
            ) as T
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
            getDepartures = { id -> WearGraph.transitlandClient().getDepartures(id) },
            favoritesManager = WearGraph.favoritesManager(context),
            stop = stop,
        ) as T
}
