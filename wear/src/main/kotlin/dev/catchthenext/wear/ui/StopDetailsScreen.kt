package dev.catchthenext.wear.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavController
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.Text
import com.google.android.horologist.compose.layout.ScalingLazyColumnDefaults
import dev.catchthenext.android.ui.DetailsUi
import dev.catchthenext.android.ui.StopDetailsViewModel

@Composable
fun StopDetailsScreen(navController: NavController, viewModel: StopDetailsViewModel) {
    val ui by viewModel.ui.collectAsState()

    when (val state = ui) {
        is DetailsUi.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Text("Loading departures…")
            }
        }
        is DetailsUi.Error -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(state.msg)
        }
        is DetailsUi.Loaded -> {
            val feedNames = state.feedNames()
            ScalingLazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = ScalingLazyColumnDefaults.padding(
                    first = ScalingLazyColumnDefaults.ItemType.Text,
                    last = ScalingLazyColumnDefaults.ItemType.Text
                )()
            ) {
                item { Text(state.stop.stopName) }
                item {
                    Chip(
                        onClick = {
                            viewModel.toggleFavorite()
                            if (state.isFavorite) navController.popBackStack()
                        },
                        label = {
                            Text(if (state.isFavorite) "Remove favorite" else "Add favorite")
                        }
                    )
                }
                if (state.alerts.isNotEmpty()) {
                    items(state.alerts) { alert -> AlertItem(alert, maxLines = 4) }
                }
                if (state.departures.isEmpty()) {
                    item { Text("No upcoming departures") }
                } else {
                    val multiAgency = state.departures.mapNotNull { it.agencyName }.toSet().size > 1
                    items(state.departures) { departure ->
                        if (multiAgency && departure.agencyName != null) {
                            Text("${departure.displayString()} · ${departure.agencyName}")
                        } else {
                            Text(departure.displayString())
                        }
                    }
                }
                if (feedNames.isNotEmpty()) {
                    item {
                        Chip(
                            onClick = { navController.navigate("about") },
                            label = { Text("Data sources") },
                            secondaryLabel = {
                                Text(if (feedNames.size == 1) feedNames.first() else "Multiple feeds")
                            },
                            colors = ChipDefaults.secondaryChipColors(),
                        )
                    }
                }
            }
        }
    }
}
