package dev.catchthenext.wear.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavController
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Text
import com.google.android.horologist.compose.layout.ScalingLazyColumnDefaults
import dev.catchthenext.wear.location.formatDistance
import dev.catchthenext.wear.storage.DistanceUnit

@Composable
fun SettingsScreen(navController: NavController, viewModel: SettingsViewModel) {
    val unit by viewModel.distanceUnit.collectAsState()
    val thresholdMeters by viewModel.thresholdMeters.collectAsState()

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = ScalingLazyColumnDefaults.padding(
            first = ScalingLazyColumnDefaults.ItemType.Text,
            last = ScalingLazyColumnDefaults.ItemType.Chip
        )()
    ) {
        item { Text("Settings") }

        item {
            Chip(
                onClick = {
                    val next = if (unit == DistanceUnit.MILES) DistanceUnit.KM else DistanceUnit.MILES
                    viewModel.setUnit(next)
                },
                label = { Text("Units: ${if (unit == DistanceUnit.MILES) "mi" else "km"}") },
                secondaryLabel = { Text("Tap to switch") },
                colors = ChipDefaults.secondaryChipColors(),
            )
        }

        item {
            Chip(
                onClick = { navController.navigate("settings/threshold") },
                label = { Text(formatDistance(thresholdMeters.toDouble(), unit)) },
                secondaryLabel = { Text("Nearby stop range") },
                colors = ChipDefaults.secondaryChipColors(),
            )
        }

        item {
            Chip(
                onClick = { navController.navigate("about") },
                label = { Text("About") },
                secondaryLabel = { Text("Data sources & version") },
                colors = ChipDefaults.secondaryChipColors(),
            )
        }
    }
}
