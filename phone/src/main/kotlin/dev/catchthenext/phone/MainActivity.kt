package dev.catchthenext.phone

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.glance.appwidget.updateAll
import dev.catchthenext.phone.widget.DeparturesWidget
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
import dev.catchthenext.android.ui.AboutViewModel
import dev.catchthenext.android.ui.AddStopViewModel
import dev.catchthenext.android.ui.DeparturesViewModel
import dev.catchthenext.android.ui.FavoritesViewModel
import dev.catchthenext.android.ui.SettingsViewModel
import dev.catchthenext.android.ui.StopConfirmViewModel
import dev.catchthenext.android.ui.StopDetailsViewModel
import dev.catchthenext.model.Stop
import dev.catchthenext.phone.ui.AboutScreen
import dev.catchthenext.phone.ui.AddStopScreen
import dev.catchthenext.phone.ui.AppTheme
import dev.catchthenext.phone.ui.DeparturesScreen
import dev.catchthenext.phone.ui.FavoritesScreen
import dev.catchthenext.phone.ui.SettingsScreen
import dev.catchthenext.phone.ui.StopConfirmScreen
import dev.catchthenext.phone.ui.StopDetailsScreen

class MainActivity : ComponentActivity() {
    override fun onResume() {
        super.onResume()
        CoroutineScope(Dispatchers.IO).launch { DeparturesWidget().updateAll(applicationContext) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
            AddStopScreen(navController = navController, viewModel = vm)
        }
        composable("details/{stopId}") { backStackEntry ->
            val stopId = backStackEntry.arguments?.getString("stopId")?.toLongOrNull() ?: 0L
            val detailsFactory = StopDetailsViewModelFactory(navController.context, stopId)
            val vm: StopDetailsViewModel = viewModel(factory = detailsFactory)
            StopDetailsScreen(navController = navController, viewModel = vm)
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

class StopConfirmViewModelFactory(
    private val context: Context,
    private val stop: Stop,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        StopConfirmViewModel(
            getDepartures = { id -> PhoneGraph.transitlandClient().getDepartures(id) },
            favoritesManager = PhoneGraph.favoritesManager(context),
            stop = stop,
        ) as T
}
