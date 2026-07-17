package dev.catchthenext.phone.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import dev.catchthenext.android.location.LatLon
import dev.catchthenext.android.location.formatDistance
import dev.catchthenext.android.location.haversineMeters
import dev.catchthenext.android.storage.DistanceUnitStore
import dev.catchthenext.android.storage.localeDefaultUnit
import dev.catchthenext.android.ui.AddStopUi
import dev.catchthenext.android.ui.AddStopViewModel
import dev.catchthenext.android.ui.PlaceSearchViewModel
import dev.catchthenext.model.Stop
import dev.catchthenext.phone.PhoneGraph

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddStopScreen(
    navController: NavController,
    viewModel: AddStopViewModel,
    placeSearchViewModel: PlaceSearchViewModel,
) {
    val ui by viewModel.ui.collectAsState()
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result[Manifest.permission.ACCESS_COARSE_LOCATION] == true ||
                result[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (granted && selectedTab == 0) viewModel.onPermissionGranted()
    }

    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) viewModel.onPermissionGranted()
        else launcher.launch(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        )
    }

    fun onAddStop(stop: Stop) {
        PhoneGraph.pendingConfirmStop = stop
        navController.navigate("confirm")
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add stop") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Nearby") },
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Search") },
                )
            }

            when (selectedTab) {
                0 -> NearbyTabContent(ui = ui, onAdd = ::onAddStop)
                1 -> AddStopSearchTab(
                    viewModel = viewModel,
                    placeSearchViewModel = placeSearchViewModel,
                    addStopUi = ui,
                    onAdd = ::onAddStop,
                )
            }
        }
    }
}

@Composable
private fun NearbyTabContent(ui: AddStopUi, onAdd: (Stop) -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (val state = ui) {
            is AddStopUi.PermissionNeeded -> Text("Location permission required")
            is AddStopUi.Locating -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Text("Getting GPS location…")
            }
            is AddStopUi.Empty -> Text("No stops found nearby")
            is AddStopUi.Error -> Text(state.msg)
            is AddStopUi.Loaded -> StopList(stops = state.stops, origin = state.origin, onAdd = onAdd)
        }
    }
}

@Composable
fun StopList(stops: List<Stop>, origin: LatLon?, onAdd: (Stop) -> Unit) {
    val context = LocalContext.current
    val unit by remember { DistanceUnitStore(context) }.unitFlow
        .collectAsState(initial = localeDefaultUnit())
    LazyColumn(Modifier.fillMaxSize()) {
        items(stops) { stop ->
            // Distance + routes served disambiguate same-named stops (e.g. opposite
            // sides of the street) so the right one gets favorited the first time.
            val distanceLabel = origin?.let {
                formatDistance(haversineMeters(it.lat, it.lon, stop.lat, stop.lon), unit)
            }
            val routesLabel = stop.routesServed?.joinToString(", ")?.let { "Routes: $it" }
            val supporting = listOfNotNull(distanceLabel, routesLabel).joinToString(" · ")
            ListItem(
                headlineContent = { Text(stop.stopName) },
                supportingContent = supporting.takeIf { it.isNotEmpty() }?.let { { Text(it) } },
                trailingContent = {
                    TextButton(onClick = { onAdd(stop) }) { Text("Add") }
                }
            )
            HorizontalDivider()
        }
    }
}
