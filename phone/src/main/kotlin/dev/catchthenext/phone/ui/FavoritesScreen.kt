package dev.catchthenext.phone.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavController
import dev.catchthenext.android.location.formatDistance
import dev.catchthenext.android.location.haversineMeters
import dev.catchthenext.android.ui.FavoritesViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesScreen(navController: NavController, viewModel: FavoritesViewModel) {
    val favorites by viewModel.favorites.collectAsState()
    val location by viewModel.location.collectAsState()
    val unit by viewModel.distanceUnit.collectAsState()
    val locationRefreshing by viewModel.locationRefreshing.collectAsState()
    val alertsByStopId by viewModel.alertsByStopId.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Favorites") },
                actions = {
                    IconButton(
                        onClick = { viewModel.refreshLocation() },
                        enabled = !locationRefreshing,
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh location")
                    }
                }
            )
        },
        bottomBar = { MainBottomBar(navController = navController, currentRoute = "favorites") },
        floatingActionButton = {
            FloatingActionButton(onClick = { navController.navigate("add") }) {
                Icon(Icons.Default.Add, contentDescription = "Add stop")
            }
        }
    ) { padding ->
        if (favorites.isEmpty()) {
            Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text("No favorites yet — tap + to add stops")
            }
        } else {
            LazyColumn(Modifier.padding(padding)) {
                items(favorites) { stop ->
                    val distanceLabel = location?.let { loc ->
                        formatDistance(haversineMeters(loc.lat, loc.lon, stop.lat, stop.lon), unit)
                    }
                    ListItem(
                        headlineContent = { Text(stop.stopName) },
                        supportingContent = distanceLabel?.let { { Text(it) } },
                        trailingContent = {
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                if (alertsByStopId[stop.id] == true) {
                                    Icon(
                                        imageVector = Icons.Default.Warning,
                                        contentDescription = "Service alert",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                }
                                IconButton(onClick = { viewModel.removeFavorite(stop.onestopId ?: "") }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Remove")
                                }
                            }
                        },
                        modifier = Modifier.clickable { navController.navigate("details/${stop.id}") }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}
