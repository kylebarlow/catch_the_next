package dev.catchthenext.phone.widget

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import dev.catchthenext.android.location.LatLon
import dev.catchthenext.android.location.haversineMeters
import dev.catchthenext.android.storage.DistanceUnitStore
import dev.catchthenext.android.tile.CachedTileData
import dev.catchthenext.android.tile.GroupedDeparture
import dev.catchthenext.android.tile.GroupedDepartureTime
import dev.catchthenext.android.tile.StopWithDepartures
import dev.catchthenext.android.tile.TileDataStore
import dev.catchthenext.android.tile.TileState
import dev.catchthenext.android.tile.computeTileState
import dev.catchthenext.android.tile.departureColorArgb
import dev.catchthenext.android.tile.groupDepartures
import dev.catchthenext.android.tile.makeFetchNetworkDepartures
import dev.catchthenext.android.tile.timeLabel
import dev.catchthenext.android.tile.updatedAtLabel
import dev.catchthenext.model.Stop
import dev.catchthenext.phone.MainActivity
import dev.catchthenext.phone.PhoneGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

private const val MAX_WIDGET_GROUPS = 3
private const val WIDGET_CACHE_TTL_MS = 5 * 60_000L

class DeparturesWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val state = withContext(Dispatchers.IO) { fetchState(context) }
        provideContent {
            GlanceTheme {
                Column(
                    modifier = GlanceModifier
                        .fillMaxSize()
                        .background(GlanceTheme.colors.surface)
                        .padding(12.dp),
                    verticalAlignment = Alignment.Vertical.Top,
                ) {
                    WidgetContent(state)
                }
            }
        }
    }

    private suspend fun fetchState(context: Context): TileState {
        val dataStore = TileDataStore(context)
        val favoritesManager = PhoneGraph.favoritesManager(context)
        val cache = dataStore.read()
        val now = System.currentTimeMillis()
        val favorites = favoritesManager.getFavorites()

        // Fast path: serve from cache when all entries are still fresh enough
        if (favorites.isNotEmpty() && cache.nearbyDepartures.isNotEmpty()) {
            val allFresh = cache.nearbyDepartures.all { now - it.fetchedAt < WIDGET_CACHE_TTL_MS }
            val lat = cache.lat
            val lon = cache.lon
            if (allFresh && lat != null && lon != null) {
                val cached = buildReadyFromCache(cache, favorites, lat, lon)
                if (cached != null) return cached
            }
        }
        return fetchFresh(context, dataStore, favorites, cache)
    }

    private fun buildReadyFromCache(
        cache: CachedTileData,
        favorites: List<Stop>,
        lat: Double,
        lon: Double,
    ): TileState.Ready? {
        val stops = cache.nearbyDepartures.mapNotNull { cached ->
            val stop = favorites.firstOrNull { it.id == cached.stopId } ?: return@mapNotNull null
            StopWithDepartures(
                stop = stop,
                distanceMeters = haversineMeters(lat, lon, stop.lat, stop.lon),
                departures = cached.departures.filter { it.currentMinutes() >= 0 },
                fetchedAt = cached.fetchedAt,
            )
        }
        return if (stops.isEmpty()) null
        else TileState.Ready(stops, stops.minOf { it.fetchedAt })
    }

    private suspend fun fetchFresh(
        context: Context,
        dataStore: TileDataStore,
        favorites: List<Stop>,
        cache: CachedTileData,
    ): TileState {
        val client = PhoneGraph.transitlandClient()
        val threshold = DistanceUnitStore(context).thresholdMetersFlow.first()
        val lat = cache.lat
        val lon = cache.lon
        val location = if (lat != null && lon != null) LatLon(lat, lon) else null
        return computeTileState(
            favorites = favorites,
            location = location,
            hasPermission = true,
            thresholdMeters = threshold,
            fetchDepartures = makeFetchNetworkDepartures(
                getDepartures = { id -> client.getDepartures(id) },
                cache = cache,
            ),
            persistDepartures = { stops -> dataStore.updateNearbyDepartures(stops) },
        )
    }
}

@androidx.compose.runtime.Composable
private fun WidgetContent(state: TileState) {
    when (state) {
        is TileState.NoFavorites -> SimpleText("Open app to add stops")
        is TileState.NoPermission -> SimpleText("Open app to grant location")
        is TileState.NoLocation -> SimpleText("Getting location…")
        is TileState.NetworkError -> SimpleText("Network error")
        is TileState.Ready -> ReadyContent(state)
    }
}

@androidx.compose.runtime.Composable
private fun ReadyContent(state: TileState.Ready) {
    val groups = groupDepartures(state.stops).take(MAX_WIDGET_GROUPS)
    if (groups.isEmpty()) {
        SimpleText("No upcoming departures")
        return
    }
    groups.forEach { group ->
        val routeLabel = buildString {
            append(group.routeShortName)
            if (group.headsign.isNotBlank()) append(" → ${group.headsign}")
            if (group.showStopTag) append(" · ${group.stopName.take(8)}")
        }
        Text(
            text = routeLabel,
            style = TextStyle(color = GlanceTheme.colors.onSurface),
            maxLines = 1,
        )
        Row {
            group.times.forEach { time ->
                Text(
                    text = timeLabel(time.minutes),
                    style = TextStyle(
                        color = ColorProvider(Color(departureColorArgb(time.timeSource))),
                        fontWeight = FontWeight.Medium,
                    ),
                )
                Spacer(modifier = GlanceModifier.padding(end = 6.dp))
            }
        }
    }
    Text(
        text = updatedAtLabel(state.fetchedAt),
        style = TextStyle(
            color = GlanceTheme.colors.onSurfaceVariant,
            fontSize = 11.sp,
        ),
        modifier = GlanceModifier.padding(top = 4.dp),
    )
}

@androidx.compose.runtime.Composable
private fun SimpleText(text: String) {
    Text(
        text = text,
        style = TextStyle(color = GlanceTheme.colors.onSurface),
    )
}

class DeparturesWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = DeparturesWidget()
}
