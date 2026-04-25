package dev.catchthenext.wear.tile

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.wear.protolayout.ColorBuilders
import androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters
import androidx.wear.protolayout.DimensionBuilders
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
import dev.catchthenext.wear.storage.AttributionStore
import dev.catchthenext.wear.storage.DistanceUnitStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

private const val MAX_TILE_ROWS = 6

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

            val threshold = DistanceUnitStore(this@ClosestStopTileService).thresholdMetersFlow.first()

            computeTileState(
                favorites = favorites,
                location = location,
                hasPermission = hasPerm,
                thresholdMeters = threshold,
                fetchDepartures = makeFetchNetworkDepartures(client, cache),
                persistDepartures = { stops -> dataStore.updateNearbyDepartures(stops) },
            )
        }

        if (state is TileState.Ready) {
            withContext(Dispatchers.IO) {
                val feeds = state.stops.mapNotNull { it.stop.feed }
                if (feeds.isNotEmpty()) {
                    AttributionStore(this@ClosestStopTileService).recordSeen(feeds)
                }
            }
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

    private fun renderLayout(state: TileState, deviceParams: DeviceParameters): LayoutElement =
        when (state) {
            is TileState.NoFavorites -> simpleLayout(deviceParams, "Add favorites", "in app")
            is TileState.NoPermission -> simpleLayout(deviceParams, "Open app to", "grant location")
            is TileState.NoLocation -> simpleLayout(deviceParams, "Getting location…")
            is TileState.NetworkError -> simpleLayout(deviceParams, "Network error")
            is TileState.Ready -> readyLayout(state, deviceParams)
        }

    private fun simpleLayout(deviceParams: DeviceParameters, vararg lines: String): LayoutElement {
        val col = LayoutElementBuilders.Column.Builder()
        lines.forEach { line ->
            col.addContent(
                Text.Builder(this, line)
                    .setTypography(Typography.TYPOGRAPHY_CAPTION1)
                    .setColor(ColorBuilders.argb(TileColors.textPrimary))
                    .build()
            )
        }
        return PrimaryLayout.Builder(deviceParams).setContent(col.build()).build()
    }

    private fun readyLayout(state: TileState.Ready, deviceParams: DeviceParameters): LayoutElement {
        // Merge departures from all stops, sorted by minutes-until-departure
        val rows = state.stops.flatMap { swd ->
            swd.departures
                .filter { it.currentMinutes() >= 0 }
                .map { Triple(it, swd.stop, state.stops.size > 1) }
        }.sortedBy { (dep, _, _) -> dep.currentMinutes() }.take(MAX_TILE_ROWS)

        val col = LayoutElementBuilders.Column.Builder()
            .setWidth(DimensionBuilders.expand())

        rows.forEachIndexed { i, (dep, stop, showStopTag) ->
            if (i > 0) {
                col.addContent(
                    LayoutElementBuilders.Spacer.Builder()
                        .setWidth(DimensionBuilders.expand())
                        .setHeight(DimensionBuilders.dp(2f))
                        .build()
                )
            }
            col.addContent(departureRow(dep, stop.stopName, showStopTag))
        }

        val primaryLabel = if (state.stops.size == 1) {
            state.stops[0].stop.stopName.take(22)
        } else {
            "${state.stops.size} nearby stops"
        }
        val ageMinutes = (System.currentTimeMillis() - state.fetchedAt) / 60_000
        val freshness = if (ageMinutes < 1) "Live" else "${ageMinutes}m ago"

        return PrimaryLayout.Builder(deviceParams)
            .setPrimaryLabelTextContent(
                Text.Builder(this, primaryLabel)
                    .setTypography(Typography.TYPOGRAPHY_CAPTION1)
                    .setColor(ColorBuilders.argb(TileColors.textDim))
                    .build()
            )
            .setContent(col.build())
            .setSecondaryLabelTextContent(
                Text.Builder(this, freshness)
                    .setTypography(Typography.TYPOGRAPHY_CAPTION3)
                    .setColor(ColorBuilders.argb(TileColors.textDim))
                    .build()
            )
            .build()
    }

    private fun departureRow(dep: CachedDeparture, stopName: String, showStopTag: Boolean): LayoutElement {
        val mins = dep.currentMinutes()
        val timeText = if (mins <= 0L) "Now" else "${mins}m"
        val stopTag = if (showStopTag) " · ${stopName.take(6)}" else ""
        val routeLabel = buildString {
            append(dep.routeShortName)
            if (dep.headsign.isNotBlank()) append(" → ${dep.headsign.take(12)}")
            append(stopTag)
        }

        return LayoutElementBuilders.Row.Builder()
            .setWidth(DimensionBuilders.expand())
            .addContent(
                Text.Builder(this, timeText)
                    .setTypography(Typography.TYPOGRAPHY_TITLE3)
                    .setColor(ColorBuilders.argb(TileColors.accent))
                    .build()
            )
            .addContent(
                LayoutElementBuilders.Spacer.Builder()
                    .setWidth(DimensionBuilders.dp(6f))
                    .build()
            )
            .addContent(
                Text.Builder(this, routeLabel)
                    .setTypography(Typography.TYPOGRAPHY_BODY2)
                    .setColor(ColorBuilders.argb(TileColors.textPrimary))
                    .build()
            )
            .build()
    }

    override suspend fun resourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ResourceBuilders.Resources {
        return ResourceBuilders.Resources.Builder().build()
    }
}
