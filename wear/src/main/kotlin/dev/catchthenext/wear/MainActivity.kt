package dev.catchthenext.wear

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
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
import dev.catchthenext.android.tile.TileDataStore
import dev.catchthenext.android.ui.AboutViewModel
import dev.catchthenext.android.ui.AddStopViewModel
import dev.catchthenext.android.ui.DeparturesViewModel
import dev.catchthenext.android.ui.FavoritesViewModel
import dev.catchthenext.android.ui.SettingsViewModel
import dev.catchthenext.android.ui.SharedViewModelDeps
import dev.catchthenext.android.ui.StopAlertsViewModel
import dev.catchthenext.android.ui.StopConfirmViewModel
import dev.catchthenext.android.ui.StopDetailsViewModel
import dev.catchthenext.android.ui.createSharedViewModel
import dev.catchthenext.model.Stop
import dev.catchthenext.wear.ui.AboutScreen
import dev.catchthenext.wear.ui.AddStopScreen
import dev.catchthenext.wear.ui.DeparturesScreen
import dev.catchthenext.wear.ui.FavoritesScreen
import dev.catchthenext.wear.ui.SettingsScreen
import dev.catchthenext.wear.ui.SettingsThresholdScreen
import dev.catchthenext.wear.ui.StopAlertsScreen
import dev.catchthenext.wear.ui.StopConfirmScreen
import dev.catchthenext.wear.ui.StopDetailsScreen
import dev.catchthenext.android.sync.FavoritesSyncListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
        composable("alerts/{stopId}") { backStackEntry ->
            val stopId = backStackEntry.arguments?.getString("stopId")?.toLongOrNull() ?: 0L
            val alertsFactory = StopAlertsViewModelFactory(navController.context, stopId)
            val vm: StopAlertsViewModel = viewModel(factory = alertsFactory)
            StopAlertsScreen(navController = navController, viewModel = vm)
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
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
        val deps = SharedViewModelDeps(
            context = context,
            client = WearGraph.transitlandClient(),
            favoritesManager = WearGraph.favoritesManager(context),
            syncStateStore = WearGraph.syncStateStore(context),
            peerLabel = "phone",
        )
        return createSharedViewModel(modelClass, deps) as? T
            ?: throw IllegalArgumentException("Unknown ViewModel: $modelClass")
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

class StopAlertsViewModelFactory(
    private val context: Context,
    private val stopId: Long,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val dataStore = TileDataStore(context)
        val favoritesManager = WearGraph.favoritesManager(context)
        return StopAlertsViewModel(
            stopId = stopId,
            readAlerts = { id ->
                dataStore.read().nearbyDepartures
                    .firstOrNull { it.stopId == id }?.alerts.orEmpty()
            },
            getStopName = { id ->
                favoritesManager.getFavorites().firstOrNull { it.id == id }?.stopName
            },
        ) as T
    }
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
