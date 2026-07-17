package dev.catchthenext.phone.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import dev.catchthenext.android.ui.DetailsUi
import dev.catchthenext.android.ui.StopDetailsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StopDetailsScreen(navController: NavController, viewModel: StopDetailsViewModel) {
    val ui by viewModel.ui.collectAsState()

    val loaded = ui as? DetailsUi.Loaded
    val title = loaded?.stop?.displayName ?: "Stop details"

    var showRenameDialog by remember { mutableStateOf(false) }
    var showFilterDialog by remember { mutableStateOf(false) }
    var showWalkTimeDialog by remember { mutableStateOf(false) }

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
                    if (loaded?.isFavorite == true) {
                        IconButton(onClick = { showRenameDialog = true }) {
                            Icon(Icons.Default.Edit, contentDescription = "Rename")
                        }
                        if (loaded.availableRoutes().size > 1) {
                            IconButton(onClick = { showFilterDialog = true }) {
                                Icon(Icons.Default.FilterList, contentDescription = "Filter routes")
                            }
                        }
                        IconButton(onClick = { showWalkTimeDialog = true }) {
                            Icon(Icons.Default.DirectionsWalk, contentDescription = "Walk time")
                        }
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
                    val feedNames = state.feedNames()
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
                            if (state.stop.nickname != null) {
                                item {
                                    Text(
                                        text = state.stop.stopName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                    )
                                }
                            }
                            if (!state.stop.shownRoutes.isNullOrEmpty()) {
                                item {
                                    Text(
                                        text = "Route filter: ${state.stop.shownRoutes!!.joinToString(", ")} — this screen shows all routes",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                    )
                                }
                            }
                            if (state.alerts.isNotEmpty()) {
                                items(state.alerts) { alert -> AlertCard(alert) }
                            }
                            items(state.departures) { departure ->
                                DepartureRow(departure, showAgency = multiAgency)
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

    if (showRenameDialog && loaded != null) {
        RenameDialog(
            currentNickname = loaded.stop.nickname,
            stopName = loaded.stop.stopName,
            onDismiss = { showRenameDialog = false },
            onSave = { nickname ->
                viewModel.setNickname(nickname)
                showRenameDialog = false
            },
        )
    }

    if (showFilterDialog && loaded != null) {
        RouteFilterDialog(
            routes = loaded.availableRoutes(),
            shownRoutes = loaded.stop.shownRoutes,
            onDismiss = { showFilterDialog = false },
            onSave = { routes ->
                viewModel.setShownRoutes(routes)
                showFilterDialog = false
            },
        )
    }

    if (showWalkTimeDialog && loaded != null) {
        WalkTimeDialog(
            currentMinutes = loaded.stop.walkMinutesOverride,
            onDismiss = { showWalkTimeDialog = false },
            onSave = { minutes ->
                viewModel.setWalkMinutesOverride(minutes)
                showWalkTimeDialog = false
            },
        )
    }
}

/**
 * Overrides the distance-based walk-time estimate used for "leave now" nudges — useful when
 * the straight-line estimate is wrong (crossing a highway, an indirect path, etc).
 */
@Composable
private fun WalkTimeDialog(
    currentMinutes: Int?,
    onDismiss: () -> Unit,
    onSave: (Int?) -> Unit,
) {
    var text by remember { mutableStateOf(currentMinutes?.toString() ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Walk time to stop") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter { c -> c.isDigit() } },
                    label = { Text("Minutes") },
                    placeholder = { Text("Estimated from distance") },
                    singleLine = true,
                )
                Text(
                    text = "Overrides the distance-based estimate used for \"leave now\" alerts. " +
                        "Leave empty to estimate automatically.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text.toIntOrNull()) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun RenameDialog(
    currentNickname: String?,
    stopName: String,
    onDismiss: () -> Unit,
    onSave: (String?) -> Unit,
) {
    var text by remember { mutableStateOf(currentNickname ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename favorite") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Nickname") },
                    placeholder = { Text(stopName) },
                    singleLine = true,
                )
                Text(
                    text = "Leave empty to use the stop name.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text.takeIf { it.isNotBlank() }) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * Per-favorite route filter editor (checkbox list of route short names seen at this stop).
 * All checked = no filter stored, so newly appearing routes stay visible by default.
 */
@Composable
private fun RouteFilterDialog(
    routes: List<String>,
    shownRoutes: List<String>?,
    onDismiss: () -> Unit,
    onSave: (List<String>?) -> Unit,
) {
    val checked = remember {
        mutableStateOf(
            if (shownRoutes.isNullOrEmpty()) routes.toSet() else shownRoutes.toSet()
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Shown routes") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = "Departures on unchecked routes are hidden from lists, widgets, and the watch tile.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                routes.forEach { route ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                checked.value = if (route in checked.value) checked.value - route
                                else checked.value + route
                            },
                    ) {
                        Checkbox(
                            checked = route in checked.value,
                            onCheckedChange = { on ->
                                checked.value = if (on) checked.value + route else checked.value - route
                            },
                        )
                        Text(route)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                // At least one route must stay checked — an empty filter would mean "show all"
                // in storage, which is confusing; disable Save instead.
                enabled = checked.value.isNotEmpty(),
                onClick = {
                    val all = checked.value.containsAll(routes)
                    onSave(if (all) null else routes.filter { it in checked.value })
                },
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
