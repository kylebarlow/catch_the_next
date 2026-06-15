package dev.catchthenext.phone

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.catchthenext.android.tile.TileDataStore
import dev.catchthenext.android.ui.AboutViewModel
import dev.catchthenext.android.ui.AddStopViewModel
import dev.catchthenext.android.ui.DeparturesViewModel
import dev.catchthenext.android.ui.FavoritesViewModel
import dev.catchthenext.android.ui.PlaceSearchViewModel
import dev.catchthenext.android.ui.SettingsViewModel
import dev.catchthenext.android.ui.StopAlertsViewModel
import dev.catchthenext.android.ui.StopConfirmViewModel
import dev.catchthenext.android.ui.StopDetailsViewModel
import dev.catchthenext.model.Stop
import dev.catchthenext.phone.ui.AboutScreen
import dev.catchthenext.phone.ui.AddStopScreen
import dev.catchthenext.phone.ui.AppTheme
import dev.catchthenext.phone.ui.DeparturesScreen
import dev.catchthenext.phone.ui.FavoritesScreen
import dev.catchthenext.phone.ui.SettingsScreen
import dev.catchthenext.phone.ui.StopAlertsScreen
import dev.catchthenext.phone.ui.StopConfirmScreen
import dev.catchthenext.phone.ui.StopDetailsScreen

class MainActivity : ComponentActivity() {
    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* DeparturesViewModel re-checks on next state refresh */ }

    override fun onResume() {
        super.onResume()
        CoroutineScope(Dispatchers.IO).launch {
            PhoneGraph.favoritesSyncController().reconcile(
                applicationContext,
                PhoneGraph.syncStateStore(applicationContext),
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.takeIf { it.scheme == "catchthenext" && it.host == "stop" }
            ?.lastPathSegment
            ?.let { PhoneGraph.pendingDeepLinkStopId = it }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Handle deep link from a notification tap
        intent?.data?.takeIf { it.scheme == "catchthenext" && it.host == "stop" }
            ?.lastPathSegment
            ?.let { PhoneGraph.pendingDeepLinkStopId = it }

        val missing = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) locationPermissionLauncher.launch(missing.toTypedArray())
        setContent {
            val factory = PhoneViewModelFactory(applicationContext)
            AppTheme {
                val navController = rememberNavController()
                PhoneNavGraph(navController, factory)
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun PhoneNavGraph(navController: NavHostController, factory: PhoneViewModelFactory) {
    // Consume any pending deep link (e.g. from a Live Update notification tap)
    androidx.compose.runtime.LaunchedEffect(Unit) {
        PhoneGraph.pendingDeepLinkStopId?.let { onestopId ->
            PhoneGraph.pendingDeepLinkStopId = null
            // onestopId may be a Long stopId or an onestop string; try Long first
            val stopId = onestopId.toLongOrNull()
            if (stopId != null) {
                navController.navigate("details/$stopId")
            }
        }
    }

    NavHost(navController = navController, startDestination = "departures") {
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
            val placeSearchVm: PlaceSearchViewModel = viewModel(factory = factory)
            AddStopScreen(
                navController = navController,
                viewModel = vm,
                placeSearchViewModel = placeSearchVm,
            )
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
            val stop = PhoneGraph.pendingConfirmStop
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
        composable("about") {
            val vm: AboutViewModel = viewModel(factory = factory)
            AboutScreen(viewModel = vm)
        }
    }
}

class StopDetailsViewModelFactory(
    private val context: Context,
    private val stopId: Long,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        StopDetailsViewModel(
            getDepartures = { id -> PhoneGraph.transitlandClient().getDepartures(id) },
            favoritesManager = PhoneGraph.favoritesManager(context),
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
        val favoritesManager = PhoneGraph.favoritesManager(context)
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
            getDepartures = { id -> PhoneGraph.transitlandClient().getDepartures(id).departures },
            favoritesManager = PhoneGraph.favoritesManager(context),
            stop = stop,
        ) as T
}
