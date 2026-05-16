package dev.catchthenext.phone.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.DirectionsBus
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import dev.catchthenext.android.ui.DetailsUi
import dev.catchthenext.android.ui.StopDetailsViewModel
import dev.catchthenext.model.Alert
import dev.catchthenext.model.AlertSeverity
import dev.catchthenext.phone.PhoneGraph

@Composable
private fun AlertCard(alert: Alert) {
    val uriHandler = LocalUriHandler.current
    val containerColor = when (alert.severityLevel) {
        AlertSeverity.SEVERE -> MaterialTheme.colorScheme.errorContainer
        AlertSeverity.WARNING -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(alert.headerText.orEmpty(), fontWeight = FontWeight.Bold)
            }
            val desc = alert.descriptionText
            if (!desc.isNullOrBlank()) {
                Text(desc, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
            }
            val url = alert.url
            if (!url.isNullOrBlank()) {
                TextButton(onClick = { uriHandler.openUri(url) }) { Text("More info") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StopDetailsScreen(navController: NavController, viewModel: StopDetailsViewModel) {
    val ui by viewModel.ui.collectAsState()
    val context = LocalContext.current
    val controller = PhoneGraph.liveUpdateController(context)
    val trackingState by controller.trackingState.collectAsState()

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
                actions = {
                    if (ui is DetailsUi.Loaded) {
                        val loaded = ui as DetailsUi.Loaded
                        val isTracking = trackingState?.stopId == loaded.stop.id
                        IconButton(onClick = {
                            if (isTracking) controller.stop()
                            else controller.start(loaded.stop)
                        }) {
                            Icon(
                                imageVector = if (isTracking) Icons.Default.DirectionsBus else Icons.Outlined.DirectionsBus,
                                contentDescription = if (isTracking) "Stop tracking" else "Track departures",
                            )
                        }
                        IconButton(onClick = {
                            viewModel.toggleFavorite()
                            if (loaded.isFavorite) navController.popBackStack()
                        }) {
                            Icon(
                                imageVector = if (loaded.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                contentDescription = if (loaded.isFavorite) "Remove favorite" else "Add favorite",
                            )
                        }
                    }
                }
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
