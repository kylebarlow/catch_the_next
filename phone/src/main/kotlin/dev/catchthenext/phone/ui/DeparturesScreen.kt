package dev.catchthenext.phone.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import dev.catchthenext.android.tile.GroupedDeparture
import dev.catchthenext.android.tile.TileState
import dev.catchthenext.android.tile.freshnessLabel
import dev.catchthenext.android.tile.groupDepartures
import dev.catchthenext.android.tile.timeLabel
import dev.catchthenext.android.ui.DeparturesUi
import dev.catchthenext.android.ui.DeparturesViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeparturesScreen(navController: NavController, viewModel: DeparturesViewModel) {
    val ui by viewModel.ui.collectAsState()
    val isLoading = ui is DeparturesUi.Loading
    val context = LocalContext.current

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { viewModel.refresh() }

    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            permLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }
    }

    var menuExpanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Catch The Next") },
                actions = {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text("Settings") },
                            onClick = { menuExpanded = false; navController.navigate("settings") }
                        )
                        DropdownMenuItem(
                            text = { Text("About") },
                            onClick = { menuExpanded = false; navController.navigate("about") }
                        )
                    }
                }
            )
        },
        bottomBar = { MainBottomBar(navController = navController, currentRoute = "departures") }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = isLoading,
            onRefresh = { viewModel.refresh(force = true) },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            when (val state = ui) {
                is DeparturesUi.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                is DeparturesUi.Loaded -> DeparturesContent(state.tileState, navController)
            }
        }
    }
}

@Composable
private fun DeparturesContent(state: TileState, navController: NavController) {
    when (state) {
        is TileState.NoPermission -> CenteredMessage("Location permission required")
        is TileState.NoFavorites -> Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("No favorites yet")
            Button(onClick = { navController.navigate("favorites") }) { Text("Manage favorites") }
        }
        is TileState.NoLocation -> CenteredMessage("Getting location…")
        is TileState.NetworkError -> CenteredMessage("Network error: ${state.message}")
        is TileState.Ready -> ReadyContent(state)
    }
}

@Composable
private fun ReadyContent(state: TileState.Ready) {
    val groups = groupDepartures(
        stops = state.stops,
        filter = { it.currentMinutes() in 0..59 },
    )
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (groups.isEmpty()) {
            item { Text("No departures in the next hour") }
        } else {
            items(groups) { group -> DepartureCard(group) }
        }
        item {
            Text(
                text = freshnessLabel(state.fetchedAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun DepartureCard(group: GroupedDeparture) {
    val routeLabel = buildString {
        append(group.routeShortName)
        if (group.headsign.isNotBlank()) append(" → ${group.headsign}")
        if (group.showStopTag) append(" · ${group.stopName}")
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(routeLabel, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = group.minutesList.joinToString("  ") { timeLabel(it) },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun CenteredMessage(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(text) }
}
