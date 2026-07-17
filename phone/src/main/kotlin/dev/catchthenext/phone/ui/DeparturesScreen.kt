package dev.catchthenext.phone.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.DirectionsBus
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import dev.catchthenext.android.tile.GroupedDeparture
import dev.catchthenext.android.tile.GroupedDepartureTime
import dev.catchthenext.android.tile.TileState
import dev.catchthenext.android.tile.departureColorArgb
import dev.catchthenext.android.tile.freshnessLabel
import dev.catchthenext.android.tile.groupDepartures
import dev.catchthenext.android.tile.timeLabel
import dev.catchthenext.android.ui.DeparturesUi
import dev.catchthenext.android.ui.DeparturesViewModel
import dev.catchthenext.model.Stop
import dev.catchthenext.phone.PhoneGraph

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeparturesScreen(navController: NavController, viewModel: DeparturesViewModel) {
    val ui by viewModel.ui.collectAsState()
    val isRefreshing = ui is DeparturesUi.Loaded && (ui as DeparturesUi.Loaded).isRefreshing
    val context = LocalContext.current
    val controller = PhoneGraph.liveUpdateController(context)
    val trackingState by controller.trackingState.collectAsState()

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { viewModel.refresh() }

    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            permLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.refresh(force = false)
        }
    }

    var menuExpanded by remember { mutableStateOf(false) }

    val isTracking = trackingState != null
    val readyState = (ui as? DeparturesUi.Loaded)?.tileState as? TileState.Ready
    val closestStop = readyState?.stops?.firstOrNull()?.stop

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Catch The Next") },
                actions = {
                    if (isTracking || closestStop != null) {
                        IconButton(onClick = {
                            if (isTracking) controller.stop()
                            else closestStop?.let { controller.start(it) }
                        }) {
                            Icon(
                                imageVector = if (isTracking) Icons.Default.DirectionsBus else Icons.Outlined.DirectionsBus,
                                contentDescription = if (isTracking) "Stop tracking" else "Track departures",
                            )
                        }
                    }
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text("Settings") },
                            onClick = { menuExpanded = false; navController.navigate("settings") }
                        )
                        DropdownMenuItem(
                            text = { Text("About") },
                            onClick = { menuExpanded = false; navController.navigate("about") }
                        )
                    }
                }
            )
        },
        bottomBar = { MainBottomBar(navController = navController, currentRoute = "departures") }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { viewModel.refresh(force = true) },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            when (val state = ui) {
                is DeparturesUi.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                is DeparturesUi.Loaded -> DeparturesContent(
                    state = state.tileState,
                    navController = navController,
                    onTrack = { stop -> controller.start(stop) },
                )
            }
        }
    }
}

@Composable
private fun DeparturesContent(state: TileState, navController: NavController, onTrack: (Stop) -> Unit) {
    Box(modifier = Modifier.fillMaxSize()) {
        when (state) {
            is TileState.NoPermission -> CenteredMessage("Location permission required")
            is TileState.NoFavorites -> Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("Shows departures near your saved stops")
                Button(onClick = { navController.navigate("add") }) { Text("Add a stop") }
            }
            is TileState.NoLocation -> CenteredMessage("Getting location…")
            is TileState.NetworkError -> CenteredMessage("Network error: ${state.message}")
            is TileState.Ready -> ReadyContent(state, navController, onTrack)
        }
    }
}

@Composable
private fun ReadyContent(state: TileState.Ready, navController: NavController, onTrack: (Stop) -> Unit) {
    // Extended from 60 to 120 min so infrequent lines (ferries, commuter buses)
    // aren't shown as empty; absolute clock times (2.3) keep the longer horizon readable.
    val groups = groupDepartures(
        stops = state.stops,
        filter = { it.currentMinutes() in 0..119 },
    )
    val multiAgency = groups.mapNotNull { it.agencyName }.toSet().size > 1
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (groups.isEmpty()) {
            item { Text("No departures in the next 2 hours") }
        } else {
            items(groups) { group ->
                DepartureCard(
                    group = group,
                    showAgency = multiAgency,
                    onOpen = { navController.navigate("details/${group.stop.id}") },
                    onTrack = { onTrack(group.stop) },
                    onOpenAlert = { navController.navigate("alerts/${group.stop.id}") },
                )
            }
        }
        item {
            Text(
                text = freshnessLabel(state.fetchedAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DepartureCard(
    group: GroupedDeparture,
    showAgency: Boolean = false,
    onOpen: () -> Unit,
    onTrack: () -> Unit,
    onOpenAlert: () -> Unit,
) {
    val routeLabel = buildString {
        append(group.routeShortName)
        if (group.headsign.isNotBlank()) append(" → ${group.headsign}")
        if (group.showStopTag) append(" · ${group.stopName}")
    }
    // Tap opens stop details; long-press keeps the quick "track this stop" shortcut.
    Card(modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onOpen, onLongClick = onTrack)) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    if (group.hasAlert) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Service alert",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Text(routeLabel, style = MaterialTheme.typography.bodyMedium)
                }
                group.alertHeadline?.let { headline ->
                    Text(
                        text = headline,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.clickable(onClick = onOpenAlert),
                    )
                }
                if (showAgency) {
                    group.agencyName?.let { agency ->
                        Text(
                            text = agency,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    group.times.forEach { time ->
                        val label = time.clockTime?.let { "${timeLabel(time.minutes)} · $it" } ?: timeLabel(time.minutes)
                        Text(
                            text = label,
                            style = MaterialTheme.typography.titleMedium,
                            color = Color(departureColorArgb(time.timeSource)),
                        )
                    }
                }
            }
            IconButton(onClick = onTrack) {
                Icon(
                    imageVector = Icons.Outlined.DirectionsBus,
                    contentDescription = "Track this stop",
                )
            }
        }
    }
}

@Composable
private fun CenteredMessage(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(text) }
}
