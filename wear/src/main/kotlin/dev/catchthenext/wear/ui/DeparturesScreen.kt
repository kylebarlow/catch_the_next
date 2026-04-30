package dev.catchthenext.wear.ui

import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Icon
import androidx.navigation.NavController
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.google.android.horologist.compose.layout.ScalingLazyColumnDefaults
import dev.catchthenext.android.tile.GroupedDeparture
import dev.catchthenext.android.tile.TileState
import dev.catchthenext.android.tile.departureColorArgb
import dev.catchthenext.android.tile.freshnessLabel
import dev.catchthenext.android.tile.groupDepartures
import dev.catchthenext.android.tile.timeLabel
import dev.catchthenext.android.ui.DeparturesUi
import dev.catchthenext.android.ui.DeparturesViewModel
import dev.catchthenext.wear.tile.TileColors

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
    val groups = groupDepartures(
        stops = state.stops,
        filter = { it.currentMinutes() in 0..59 },
    )

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = ScalingLazyColumnDefaults.padding(
            first = ScalingLazyColumnDefaults.ItemType.Text,
            last = ScalingLazyColumnDefaults.ItemType.Chip,
        )()
    ) {
        if (state.stops.size == 1) {
            item { Text(state.stops[0].stop.stopName) }
        }

        if (groups.isEmpty()) {
            item { Text("No departures in the next hour") }
        } else {
            items(groups) { group -> GroupedDepartureRow(group) }
        }

        item { Text(freshnessLabel(state.fetchedAt), color = Color(TileColors.textDim)) }

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
private fun GroupedDepartureRow(group: GroupedDeparture) {
    val routeLabel = buildString {
        append(group.routeShortName)
        if (group.headsign.isNotBlank()) append(" → ${group.headsign}")
        if (group.showStopTag) append(" · ${group.stopName}")
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (group.hasAlert) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = "Service alert",
                    tint = Color(0xFFFFC107),
                    modifier = Modifier.size(12.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            Text(
                text = routeLabel,
                modifier = Modifier.basicMarquee(),
                maxLines = 1,
            )
        }
        Row {
            group.times.forEachIndexed { i, time ->
                if (i > 0) Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = timeLabel(time.minutes),
                    color = Color(departureColorArgb(time.timeSource)),
                    style = MaterialTheme.typography.title3,
                )
            }
        }
    }
}
