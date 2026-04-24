package dev.catchthenext.wear.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavController
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Text
import com.google.android.horologist.compose.layout.ScalingLazyColumnDefaults
import dev.catchthenext.wear.location.formatDistance
import dev.catchthenext.wear.location.haversineMeters
import dev.catchthenext.wear.storage.DistanceUnit

@Composable
fun FavoritesScreen(navController: NavController, viewModel: FavoritesViewModel) {
    val favorites by viewModel.favorites.collectAsState()
    val location by viewModel.location.collectAsState()
    val unit by viewModel.distanceUnit.collectAsState()

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = ScalingLazyColumnDefaults.padding(
            first = ScalingLazyColumnDefaults.ItemType.Text,
            last = ScalingLazyColumnDefaults.ItemType.Text
        )()
    ) {
        item { Text("Favorite transit stops") }
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
                )
            }
        }
        item {
            Chip(
                onClick = { viewModel.toggleUnit() },
                label = { Text("Units: ${if (unit == DistanceUnit.MILES) "mi" else "km"}") },
                colors = ChipDefaults.secondaryChipColors(),
            )
        }
    }
}
