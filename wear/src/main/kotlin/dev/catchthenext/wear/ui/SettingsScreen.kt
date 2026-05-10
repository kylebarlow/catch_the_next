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
import dev.catchthenext.android.location.formatDistance
import dev.catchthenext.android.storage.DistanceUnit
import dev.catchthenext.android.ui.SettingsViewModel
import dev.catchthenext.android.ui.SyncStatus

@Composable
fun SettingsScreen(navController: NavController, viewModel: SettingsViewModel) {
    val unit by viewModel.distanceUnit.collectAsState()
    val thresholdMeters by viewModel.thresholdMeters.collectAsState()
    val syncStatus by viewModel.syncStatus.collectAsState()
    val peerReachable by viewModel.peerReachable.collectAsState()
    val syncChipLabel = "Sync favorites with ${viewModel.peerLabel.ifEmpty { "peer" }}"
    val syncSecondaryLabel = when (syncStatus) {
        SyncStatus.SYNCING -> "Syncing…"
        SyncStatus.DONE -> "Synced"
        SyncStatus.ERROR -> "Failed"
        SyncStatus.IDLE -> if (peerReachable) "${viewModel.peerLabel.replaceFirstChar { it.uppercase() }} connected".trimStart()
                           else "${viewModel.peerLabel.replaceFirstChar { it.uppercase() }} offline".trimStart()
    }

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
                onClick = { viewModel.syncNow() },
                enabled = syncStatus == SyncStatus.IDLE,
                label = { Text(syncChipLabel) },
                secondaryLabel = { if (syncSecondaryLabel.isNotEmpty()) Text(syncSecondaryLabel) },
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
