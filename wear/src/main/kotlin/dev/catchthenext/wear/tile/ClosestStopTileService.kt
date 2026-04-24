package dev.catchthenext.wear.tile

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.wear.protolayout.ColorBuilders
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
import dev.catchthenext.wear.WearGraph
import dev.catchthenext.wear.location.LatLon
import dev.catchthenext.wear.location.LocationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalHorologistApi::class)
class ClosestStopTileService : SuspendingTileService() {

    override suspend fun tileRequest(requestParams: RequestBuilders.TileRequest): TileBuilders.Tile {
        val deviceParams = requestParams.deviceConfiguration
        DepartureWorker.schedule(this)

        val favoritesManager = WearGraph.favoritesManager(this)
        val client = WearGraph.transitlandClient()
        val locationProvider = LocationProvider(this)
        val dataStore = TileDataStore(this)

        val state = withContext(Dispatchers.IO) {
            val hasPerm = ContextCompat.checkSelfPermission(
                this@ClosestStopTileService, Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            val favorites = favoritesManager.getFavorites()

            val freshLocation = if (hasPerm) locationProvider.currentLocation() else null
            if (freshLocation != null) dataStore.updateLocation(freshLocation.lat, freshLocation.lon)

            val cache = dataStore.read()
            val lat = freshLocation?.lat ?: cache.lat
            val lon = freshLocation?.lon ?: cache.lon
            val location = if (lat != null && lon != null) LatLon(lat, lon) else null

            computeTileState(
                favorites = favorites,
                location = location,
                hasPermission = hasPerm,
                fetchDepartures = makeFetchDepartures(favorites, dataStore, client, cache),
            )
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
                state.departures.take(2).forEach { dep ->
                    val mins = dep.currentMinutes()
                    val time = if (mins <= 0L) "Now" else "${mins}m"
                    val direction = if (dep.headsign.isNotBlank()) " → ${dep.headsign.take(12)}" else ""
                    add("$time · ${dep.routeShortName}$direction")
                }
                val ageMinutes = (System.currentTimeMillis() - state.fetchedAt) / 60_000
                add(if (ageMinutes < 1) "Live" else "${ageMinutes}m ago")
            }
        }

        val white = ColorBuilders.argb(0xFFFFFFFF.toInt())
        val column = LayoutElementBuilders.Column.Builder()
        lines.forEach { line ->
            column.addContent(
                Text.Builder(this, line)
                    .setTypography(Typography.TYPOGRAPHY_CAPTION1)
                    .setColor(white)
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
}
