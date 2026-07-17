package dev.catchthenext.phone.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.zIndex
import androidx.navigation.NavController
import dev.catchthenext.android.location.formatDistance
import dev.catchthenext.android.location.haversineMeters
import dev.catchthenext.android.ui.FavoritesViewModel
import dev.catchthenext.model.Stop
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesScreen(navController: NavController, viewModel: FavoritesViewModel) {
    val favorites by viewModel.favorites.collectAsState()
    val location by viewModel.location.collectAsState()
    val unit by viewModel.distanceUnit.collectAsState()
    val locationRefreshing by viewModel.locationRefreshing.collectAsState()
    val alertsByStopId by viewModel.alertsByStopId.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Local copy of the list so drag-reorder is smooth; synced from the store
    // whenever a drag isn't in progress, persisted on drop.
    var localOrder by remember { mutableStateOf(favorites) }
    var draggingIndex by remember { mutableIntStateOf(-1) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    var draggedItemHeight by remember { mutableIntStateOf(0) }
    LaunchedEffect(favorites) {
        if (draggingIndex < 0) localOrder = favorites
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Favorites") },
                actions = {
                    IconButton(
                        onClick = { viewModel.refreshLocation() },
                        enabled = !locationRefreshing,
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh location")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = { MainBottomBar(navController = navController, currentRoute = "favorites") },
        floatingActionButton = {
            FloatingActionButton(onClick = { navController.navigate("add") }) {
                Icon(Icons.Default.Add, contentDescription = "Add stop")
            }
        }
    ) { padding ->
        if (localOrder.isEmpty()) {
            Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text("No favorites yet — tap + to add stops")
            }
        } else {
            LazyColumn(Modifier.padding(padding)) {
                itemsIndexed(localOrder, key = { _, stop -> stop.id }) { index, stop ->
                    val isDragging = index == draggingIndex
                    Box(
                        Modifier
                            .zIndex(if (isDragging) 1f else 0f)
                            .graphicsLayer { translationY = if (isDragging) dragOffsetY else 0f }
                            .onSizeChanged { if (isDragging) draggedItemHeight = it.height }
                    ) {
                        FavoriteRow(
                            stop = stop,
                            distanceLabel = location?.let { loc ->
                                formatDistance(haversineMeters(loc.lat, loc.lon, stop.lat, stop.lon), unit)
                            },
                            alertHeadline = alertsByStopId[stop.id].orEmpty()
                                .firstOrNull()?.headerText?.takeIf { it.isNotBlank() },
                            onOpen = { navController.navigate("details/${stop.id}") },
                            onOpenAlerts = { navController.navigate("alerts/${stop.id}") },
                            onRemove = {
                                viewModel.removeFavorite(stop)
                                scope.launch {
                                    val result = snackbarHostState.showSnackbar(
                                        message = "Removed ${stop.displayName}",
                                        actionLabel = "Undo",
                                    )
                                    if (result == SnackbarResult.ActionPerformed) {
                                        viewModel.restoreFavorite(stop)
                                    }
                                }
                            },
                            dragHandleModifier = Modifier.pointerInput(stop.id) {
                                detectDragGestures(
                                    onDragStart = {
                                        draggingIndex = localOrder.indexOfFirst { it.id == stop.id }
                                        dragOffsetY = 0f
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        dragOffsetY += dragAmount.y
                                        val height = draggedItemHeight.takeIf { it > 0 } ?: return@detectDragGestures
                                        // Swap with the neighbor once we've dragged past half its height.
                                        while (dragOffsetY > height / 2f && draggingIndex < localOrder.lastIndex) {
                                            localOrder = localOrder.swapped(draggingIndex, draggingIndex + 1)
                                            draggingIndex += 1
                                            dragOffsetY -= height
                                        }
                                        while (dragOffsetY < -height / 2f && draggingIndex > 0) {
                                            localOrder = localOrder.swapped(draggingIndex, draggingIndex - 1)
                                            draggingIndex -= 1
                                            dragOffsetY += height
                                        }
                                    },
                                    onDragEnd = {
                                        draggingIndex = -1
                                        dragOffsetY = 0f
                                        viewModel.saveOrder(localOrder)
                                    },
                                    onDragCancel = {
                                        draggingIndex = -1
                                        dragOffsetY = 0f
                                        localOrder = favorites
                                    },
                                )
                            },
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

private fun List<Stop>.swapped(a: Int, b: Int): List<Stop> =
    toMutableList().also { it[a] = this[b]; it[b] = this[a] }

@Composable
private fun FavoriteRow(
    stop: Stop,
    distanceLabel: String?,
    alertHeadline: String?,
    onOpen: () -> Unit,
    onOpenAlerts: () -> Unit,
    onRemove: () -> Unit,
    dragHandleModifier: Modifier,
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value != SwipeToDismissBoxValue.Settled) {
                onRemove()
                true
            } else {
                false
            }
        }
    )
    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            Box(
                Modifier.fillMaxSize().padding(horizontal = 24.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        },
    ) {
        // Nickname (when set) is the headline; the GTFS name drops to supporting text.
        // The alert headline (when present) is shown as its own line, tap to expand.
        val supporting = listOfNotNull(
            stop.stopName.takeIf { stop.nickname != null },
            distanceLabel,
        ).joinToString(" · ")
        ListItem(
            headlineContent = { Text(stop.displayName) },
            supportingContent = {
                Column {
                    if (supporting.isNotEmpty()) Text(supporting)
                    alertHeadline?.let { headline ->
                        Text(
                            text = headline,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.clickable(onClick = onOpenAlerts),
                        )
                    }
                }
            },
            trailingContent = {
                Icon(
                    imageVector = Icons.Default.DragHandle,
                    contentDescription = "Reorder",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = dragHandleModifier,
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpen() }
        )
    }
}
