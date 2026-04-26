package dev.catchthenext.wear.tile

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders
import androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters
import androidx.wear.protolayout.DimensionBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material.Text
import androidx.wear.protolayout.material.Typography
import androidx.wear.protolayout.material.layouts.PrimaryLayout
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import com.google.android.horologist.annotations.ExperimentalHorologistApi
import com.google.android.horologist.tiles.SuspendingTileService
import dev.catchthenext.android.location.LatLon
import dev.catchthenext.android.location.LocationProvider
import dev.catchthenext.android.storage.AttributionStore
import dev.catchthenext.android.storage.DistanceUnitStore
import dev.catchthenext.android.tile.CachedTileData
import dev.catchthenext.android.tile.DepartureWorker
import dev.catchthenext.android.tile.GroupedDeparture
import dev.catchthenext.android.tile.TileDataStore
import dev.catchthenext.android.tile.TileState
import dev.catchthenext.android.tile.computeTileState
import dev.catchthenext.android.tile.groupDepartures
import dev.catchthenext.android.tile.makeFetchNetworkDepartures
import dev.catchthenext.android.tile.timeLabel
import dev.catchthenext.android.tile.updatedAtLabel
import dev.catchthenext.wear.WearGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

private const val MAX_TILE_GROUPS = 3

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
                fetchDepartures = makeFetchNetworkDepartures({ client.getDepartures(it) }, cache),
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
                                    .setRoot(tappableLayout(renderLayout(state, deviceParams)))
                                    .build()
                            )
                            .build()
                    )
                    .build()
            )
            .build()
    }

    private fun tappableLayout(inner: LayoutElement): LayoutElement =
        LayoutElementBuilders.Box.Builder()
            .setWidth(DimensionBuilders.expand())
            .setHeight(DimensionBuilders.expand())
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setClickable(
                        ModifiersBuilders.Clickable.Builder()
                            .setId("open_app")
                            .setOnClick(
                                ActionBuilders.LaunchAction.Builder()
                                    .setAndroidActivity(
                                        ActionBuilders.AndroidActivity.Builder()
                                            .setPackageName(packageName)
                                            .setClassName("dev.catchthenext.wear.MainActivity")
                                            .build()
                                    )
                                    .build()
                            )
                            .build()
                    )
                    .build()
            )
            .addContent(inner)
            .build()

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
        val groups = groupDepartures(state.stops).take(MAX_TILE_GROUPS)

        val col = LayoutElementBuilders.Column.Builder()
            .setWidth(DimensionBuilders.expand())

        groups.forEachIndexed { i, group ->
            if (i > 0) {
                col.addContent(
                    LayoutElementBuilders.Spacer.Builder()
                        .setWidth(DimensionBuilders.expand())
                        .setHeight(DimensionBuilders.dp(4f))
                        .build()
                )
            }
            col.addContent(groupedDepartureRow(group))
        }

        val builder = PrimaryLayout.Builder(deviceParams)
        if (state.stops.size == 1) {
            builder.setPrimaryLabelTextContent(
                Text.Builder(this, state.stops[0].stop.stopName.take(22))
                    .setTypography(Typography.TYPOGRAPHY_CAPTION1)
                    .setColor(ColorBuilders.argb(TileColors.textDim))
                    .build()
            )
        }
        return builder
            .setContent(col.build())
            .setSecondaryLabelTextContent(
                Text.Builder(this, updatedAtLabel(state.fetchedAt))
                    .setTypography(Typography.TYPOGRAPHY_CAPTION3)
                    .setColor(ColorBuilders.argb(TileColors.textDim))
                    .build()
            )
            .build()
    }

    private fun groupedDepartureRow(group: GroupedDeparture): LayoutElement {
        val timesLabel = group.minutesList.joinToString("  ") { timeLabel(it) }
        val stopTag = if (group.showStopTag) " · ${group.stopName.take(8)}" else ""
        val routeLabel = buildString {
            append(group.routeShortName)
            if (group.headsign.isNotBlank()) append(" → ${group.headsign.take(14)}")
            append(stopTag)
        }

        return LayoutElementBuilders.Column.Builder()
            .setWidth(DimensionBuilders.expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_START)
            .addContent(
                Text.Builder(this, routeLabel)
                    .setTypography(Typography.TYPOGRAPHY_BODY2)
                    .setColor(ColorBuilders.argb(TileColors.textPrimary))
                    .build()
            )
            .addContent(
                Text.Builder(this, timesLabel)
                    .setTypography(Typography.TYPOGRAPHY_CAPTION1)
                    .setColor(ColorBuilders.argb(TileColors.accent))
                    .build()
            )
            .build()
    }

    override suspend fun resourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ResourceBuilders.Resources {
        return ResourceBuilders.Resources.Builder().build()
    }
}
