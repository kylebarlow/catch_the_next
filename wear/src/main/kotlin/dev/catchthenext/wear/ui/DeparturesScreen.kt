package dev.catchthenext.wear.ui

import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.google.android.horologist.compose.layout.ScalingLazyColumnDefaults
import dev.catchthenext.wear.tile.CachedDeparture
import dev.catchthenext.wear.tile.TileColors
import dev.catchthenext.wear.tile.TileState
import dev.catchthenext.wear.tile.freshnessLabel
import dev.catchthenext.wear.tile.timeLabel

@Composable
fun DeparturesScreen(navController: NavController, viewModel: DeparturesViewModel) {
    val ui by viewModel.ui.collectAsState()

    when (val state = ui) {
        is DeparturesUi.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Text("Fetching departures…")
            }
        }
        is DeparturesUi.Loaded -> when (val tileState = state.tileState) {
            is TileState.NoFavorites -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No favorites yet")
                    Chip(
                        onClick = { navController.navigate("favorites") },
                        label = { Text("Manage favorites") },
                        colors = ChipDefaults.secondaryChipColors(),
                    )
                }
            }
            is TileState.NoPermission -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Location permission required")
            }
            is TileState.NoLocation -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Getting location…")
            }
            is TileState.NetworkError -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Network error")
                    Text(tileState.message)
                }
            }
            is TileState.Ready -> DeparturesReadyContent(tileState, navController, viewModel)
        }
    }
}

@Composable
private fun DeparturesReadyContent(
    state: TileState.Ready,
    navController: NavController,
    viewModel: DeparturesViewModel,
) {
    val rows = state.stops.flatMap { swd ->
        swd.departures
            .filter { it.currentMinutes() in 0..59 }
            .take(10)
            .map { Triple(it, swd.stop.stopName, state.stops.size > 1) }
    }.sortedBy { (dep, _, _) -> dep.currentMinutes() }

    val headerText = if (state.stops.size == 1) state.stops[0].stop.stopName
                     else "${state.stops.size} nearby stops"

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = ScalingLazyColumnDefaults.padding(
            first = ScalingLazyColumnDefaults.ItemType.Text,
            last = ScalingLazyColumnDefaults.ItemType.Chip,
        )()
    ) {
        item { Text(headerText) }
        item { Text(freshnessLabel(state.fetchedAt), color = Color(TileColors.textDim)) }

        if (rows.isEmpty()) {
            item { Text("No departures in the next hour") }
        } else {
            items(rows) { (dep, stopName, showStopTag) ->
                DepartureRow(dep, stopName, showStopTag)
            }
        }

        item {
            Chip(
                onClick = { viewModel.refresh(force = true) },
                label = { Text("Refresh") },
                colors = ChipDefaults.secondaryChipColors(),
            )
        }
        item {
            Chip(
                onClick = { navController.navigate("favorites") },
                label = { Text("Manage favorites") },
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

@Composable
private fun DepartureRow(dep: CachedDeparture, stopName: String, showStopTag: Boolean) {
    val routeLabel = buildString {
        append(dep.routeShortName)
        if (dep.headsign.isNotBlank()) append(" → ${dep.headsign}")
        if (showStopTag) append(" · $stopName")
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = timeLabel(dep.currentMinutes()),
            color = Color(TileColors.accent),
            style = MaterialTheme.typography.title3,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = routeLabel,
            modifier = Modifier.basicMarquee(),
            maxLines = 1,
        )
    }
}
