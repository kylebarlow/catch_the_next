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
import dev.catchthenext.wear.storage.DistanceUnit
import kotlin.math.abs

// Predefined threshold options in meters (~0.1, 0.25, 0.5, 0.75, 1.0, 1.5, 2.0, 3.0, 5.0 miles)
private val THRESHOLD_STEPS_METERS = listOf(161, 402, 805, 1207, 1609, 2414, 3219, 4828, 8047)

private fun closestStep(meters: Int): Int =
    THRESHOLD_STEPS_METERS.minByOrNull { abs(it - meters) } ?: 1609

@Composable
fun SettingsThresholdScreen(navController: NavController, viewModel: SettingsViewModel) {
    val unit by viewModel.distanceUnit.collectAsState()
    val currentThreshold by viewModel.thresholdMeters.collectAsState()
    val selectedStep = closestStep(currentThreshold)

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = ScalingLazyColumnDefaults.padding(
            first = ScalingLazyColumnDefaults.ItemType.Text,
            last = ScalingLazyColumnDefaults.ItemType.Chip
        )()
    ) {
        item { Text("Nearby stop range") }
        item { Text("Show stops within:") }

        items(THRESHOLD_STEPS_METERS) { stepMeters ->
            val isSelected = selectedStep == stepMeters
            Chip(
                onClick = {
                    viewModel.setThresholdMeters(stepMeters)
                    navController.popBackStack()
                },
                label = { Text(formatDistance(stepMeters.toDouble(), unit)) },
                secondaryLabel = if (isSelected) ({ Text("Current") }) else null,
                colors = if (isSelected) ChipDefaults.primaryChipColors()
                         else ChipDefaults.secondaryChipColors(),
            )
        }
    }
}
