package dev.catchthenext.phone.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavController
import dev.catchthenext.android.location.formatDistance
import dev.catchthenext.android.storage.DistanceUnit
import dev.catchthenext.android.ui.SettingsViewModel
import dev.catchthenext.android.ui.SyncStatus
import dev.catchthenext.android.ui.SyncStatus.IDLE
import dev.catchthenext.android.ui.SyncStatus.SYNCING
import dev.catchthenext.android.ui.SyncStatus.DONE
import dev.catchthenext.android.ui.SyncStatus.ERROR
import kotlin.math.abs
import kotlin.math.roundToInt

private val THRESHOLD_STEPS_METERS = listOf(161, 402, 805, 1207, 1609, 2414, 3219, 4828, 8047)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(navController: NavController, viewModel: SettingsViewModel) {
    val unit by viewModel.distanceUnit.collectAsState()
    val thresholdMeters by viewModel.thresholdMeters.collectAsState()
    val syncStatus by viewModel.syncStatus.collectAsState()
    val peerReachable by viewModel.peerReachable.collectAsState()
    var showThresholdDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.syncEvents.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            ListItem(
                headlineContent = { Text("Distance unit") },
                trailingContent = {
                    SingleChoiceSegmentedButtonRow {
                        SegmentedButton(
                            selected = unit == DistanceUnit.MILES,
                            onClick = { viewModel.setUnit(DistanceUnit.MILES) },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        ) { Text("Miles") }
                        SegmentedButton(
                            selected = unit == DistanceUnit.KM,
                            onClick = { viewModel.setUnit(DistanceUnit.KM) },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        ) { Text("Kilometers") }
                    }
                }
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Nearby stop range") },
                supportingContent = { Text(formatDistance(thresholdMeters.toDouble(), unit)) },
                trailingContent = {
                    IconButton(onClick = { showThresholdDialog = true }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Change")
                    }
                }
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Sync favorites with watch") },
                supportingContent = {
                    Text(when (syncStatus) {
                        SYNCING -> "Syncing…"
                        DONE -> "Synced"
                        ERROR -> "Sync failed"
                        IDLE -> if (peerReachable) "Watch connected" else "Watch offline — syncs when connected"
                    })
                },
                trailingContent = {
                    TextButton(
                        onClick = { viewModel.syncNow() },
                        enabled = syncStatus == IDLE,
                    ) { Text("Sync") }
                }
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("About") },
                supportingContent = { Text("Data sources & version") },
                trailingContent = {
                    IconButton(onClick = { navController.navigate("about") }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "About")
                    }
                }
            )
        }
    }

    if (showThresholdDialog) {
        ThresholdDialog(
            currentMeters = thresholdMeters,
            unit = unit,
            onConfirm = { meters ->
                viewModel.setThresholdMeters(meters)
                showThresholdDialog = false
            },
            onDismiss = { showThresholdDialog = false },
        )
    }
}

@Composable
private fun ThresholdDialog(
    currentMeters: Int,
    unit: DistanceUnit,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val initialIndex = THRESHOLD_STEPS_METERS.indexOfFirst {
        abs(it - currentMeters) == THRESHOLD_STEPS_METERS.minOf { s -> abs(s - currentMeters) }
    }.coerceAtLeast(0)
    var sliderPosition by remember { mutableFloatStateOf(initialIndex.toFloat()) }
    val selectedMeters = THRESHOLD_STEPS_METERS[sliderPosition.roundToInt().coerceIn(0, THRESHOLD_STEPS_METERS.lastIndex)]

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nearby stop range") },
        text = {
            Column {
                Text(formatDistance(selectedMeters.toDouble(), unit))
                Slider(
                    value = sliderPosition,
                    onValueChange = { sliderPosition = it },
                    valueRange = 0f..(THRESHOLD_STEPS_METERS.lastIndex.toFloat()),
                    steps = THRESHOLD_STEPS_METERS.size - 2,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selectedMeters) }) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
