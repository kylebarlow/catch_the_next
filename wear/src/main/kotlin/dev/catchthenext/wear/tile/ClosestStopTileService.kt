package dev.catchthenext.wear.tile

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material.Text
import androidx.wear.protolayout.material.Typography
import androidx.wear.protolayout.material.layouts.PrimaryLayout
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import com.google.android.horologist.annotations.ExperimentalHorologistApi
import com.google.android.horologist.tiles.SuspendingTileService
import dev.catchthenext.model.Departure
import dev.catchthenext.wear.WearGraph
import dev.catchthenext.wear.location.LocationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalHorologistApi::class)
class ClosestStopTileService : SuspendingTileService() {

    override suspend fun tileRequest(requestParams: RequestBuilders.TileRequest): TileBuilders.Tile {
        val deviceParams = requestParams.deviceConfiguration
        val favoritesManager = WearGraph.favoritesManager(this)
        val client = WearGraph.transitlandClient()
        val locationProvider = LocationProvider(this)

        val state = withContext(Dispatchers.IO) {
            val favorites = favoritesManager.getFavorites()
            val hasPerm = ContextCompat.checkSelfPermission(
                this@ClosestStopTileService, Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            val location = if (hasPerm) locationProvider.currentLocation() else null
            computeTileState(favorites, location, hasPerm) { client.getDepartures(it) }
        }

        return TileBuilders.Tile.Builder()
            .setFreshnessIntervalMillis(60_000L)
            .setTileTimeline(
                TimelineBuilders.Timeline.Builder()
                    .addTimelineEntry(
                        TimelineBuilders.TimelineEntry.Builder()
                            .setLayout(
                                LayoutElementBuilders.Layout.Builder()
                                    .setRoot(renderLayout(state, deviceParams))
                                    .build()
                            )
                            .build()
                    )
                    .build()
            )
            .build()
    }

    private fun renderLayout(state: TileState, deviceParams: DeviceParameters): LayoutElement {
        val lines: List<String> = when (state) {
            is TileState.NoFavorites -> listOf("Add favorites", "in app")
            is TileState.NoPermission -> listOf("Open app to", "grant location")
            is TileState.NoLocation -> listOf("Getting location…")
            is TileState.NetworkError -> listOf("Network error")
            is TileState.Ready -> buildList {
                add(state.stop.stopName)
                state.departures.take(3).forEach { add(it.compactText()) }
            }
        }

        val column = LayoutElementBuilders.Column.Builder()
        lines.forEach { line ->
            column.addContent(
                Text.Builder(this, line)
                    .setTypography(Typography.TYPOGRAPHY_CAPTION1)
                    .build()
            )
        }

        return PrimaryLayout.Builder(deviceParams)
            .setContent(column.build())
            .build()
    }

    override suspend fun resourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ResourceBuilders.Resources {
        return ResourceBuilders.Resources.Builder().build()
    }

    private fun Departure.compactText(): String {
        val time = if (departureMinutes <= 0L) "Now" else "${departureMinutes}m"
        val route = routeShortName.ifBlank { routeLongName.take(12) }
        return "$time · $route"
    }
}
