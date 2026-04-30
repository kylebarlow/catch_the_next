package dev.catchthenext.wear.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import com.google.android.horologist.compose.layout.ScalingLazyColumnDefaults
import dev.catchthenext.android.location.formatDistance
import dev.catchthenext.android.location.haversineMeters
import dev.catchthenext.android.storage.DistanceUnit
import dev.catchthenext.android.ui.FavoritesViewModel

@Composable
fun FavoritesScreen(navController: NavController, viewModel: FavoritesViewModel) {
    val favorites by viewModel.favorites.collectAsState()
    val location by viewModel.location.collectAsState()
    val unit by viewModel.distanceUnit.collectAsState()
    val locationFetchedAt by viewModel.locationFetchedAt.collectAsState()
    val locationRefreshing by viewModel.locationRefreshing.collectAsState()
    val alertsByStopId by viewModel.alertsByStopId.collectAsState()

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = ScalingLazyColumnDefaults.padding(
            first = ScalingLazyColumnDefaults.ItemType.Text,
            last = ScalingLazyColumnDefaults.ItemType.Text
        )()
    ) {
        item { Text("Favorite transit stops") }

        // Location age indicator — remove block below to hide in prod
        item {
            val ageText = when {
                locationRefreshing -> "Getting location…"
                locationFetchedAt != null -> {
                    val mins = (System.currentTimeMillis() - locationFetchedAt!!) / 60_000
                    if (mins < 1) "Location: live" else "Location: ${mins}m ago"
                }
                else -> "Location unavailable"
            }
            Text(ageText)
        }

        item {
            Chip(
                onClick = { navController.navigate("add") },
                label = { Text("+ Add stop") },
                colors = ChipDefaults.secondaryChipColors(),
            )
        }
        if (favorites.isEmpty()) {
            item { Text("No favorites yet") }
        } else {
            items(favorites) { stop ->
                val distanceLabel = location?.let { loc ->
                    formatDistance(haversineMeters(loc.lat, loc.lon, stop.lat, stop.lon), unit)
                }
                Chip(
                    onClick = { navController.navigate("details/${stop.id}") },
                    label = { Text(stop.stopName) },
                    secondaryLabel = distanceLabel?.let { { Text(it) } },
                    icon = if (alertsByStopId[stop.id] == true) {
                        {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "Service alert",
                                tint = Color(0xFFFFC107),
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    } else null,
                )
            }
        }

        item {
            Chip(
                onClick = { viewModel.refreshLocation() },
                enabled = !locationRefreshing,
                label = { Text(if (locationRefreshing) "Getting location…" else "Refresh location") },
                colors = ChipDefaults.secondaryChipColors(),
            )
        }

        item {
            Chip(
                onClick = { navController.navigate("settings") },
                label = { Text("Settings") },
                colors = ChipDefaults.secondaryChipColors(),
            )
        }
    }
}
