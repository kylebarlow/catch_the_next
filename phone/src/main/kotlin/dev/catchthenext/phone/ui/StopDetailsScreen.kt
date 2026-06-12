package dev.catchthenext.phone.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavController
import dev.catchthenext.android.ui.DetailsUi
import dev.catchthenext.android.ui.StopDetailsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StopDetailsScreen(navController: NavController, viewModel: StopDetailsViewModel) {
    val ui by viewModel.ui.collectAsState()

    val title = when (val s = ui) {
        is DetailsUi.Loaded -> s.stop.stopName
        else -> "Stop details"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            when (val state = ui) {
                is DetailsUi.Loading -> CircularProgressIndicator()
                is DetailsUi.Error -> Text(state.msg)
                is DetailsUi.Loaded -> {
                    val feedNames = (listOfNotNull(state.stop.feed) + state.departures.mapNotNull { it.feed })
                        .distinctBy { it.feedOnestopId }
                        .mapNotNull { it.feedName ?: it.feedOnestopId }
                    val dataLabel = when (feedNames.size) {
                        0 -> null
                        1 -> "Data: ${feedNames.first()}"
                        else -> "Data: multiple feeds"
                    }
                    if (state.departures.isEmpty() && state.alerts.isEmpty()) {
                        Text("No upcoming departures")
                    } else {
                        val multiAgency = state.departures.mapNotNull { it.agencyName }.toSet().size > 1
                        LazyColumn(Modifier.fillMaxSize()) {
                            if (state.alerts.isNotEmpty()) {
                                items(state.alerts) { alert -> AlertCard(alert) }
                            }
                            items(state.departures) { departure ->
                                ListItem(
                                    headlineContent = { Text(departure.displayString()) },
                                    supportingContent = if (multiAgency) departure.agencyName?.let { agency ->
                                        { Text(agency) }
                                    } else null,
                                )
                                HorizontalDivider()
                            }
                            if (dataLabel != null) {
                                item {
                                    ListItem(
                                        headlineContent = {
                                            Text(
                                                dataLabel,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        },
                                        trailingContent = {
                                            TextButton(onClick = { navController.navigate("about") }) {
                                                Text("Sources")
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
