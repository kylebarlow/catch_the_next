package dev.catchthenext.wear.tile

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
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
import androidx.wear.tiles.TileService
import com.google.android.horologist.annotations.ExperimentalHorologistApi
import com.google.android.horologist.tiles.SuspendingTileService
import dev.catchthenext.android.location.LatLon
import dev.catchthenext.android.location.LocationProvider
import dev.catchthenext.android.location.haversineMeters
import dev.catchthenext.android.storage.DistanceUnitStore
import dev.catchthenext.android.tile.CachedTileData
import dev.catchthenext.android.tile.GroupedDeparture
import dev.catchthenext.android.tile.StopWithDepartures
import dev.catchthenext.android.tile.TileDataStore
import dev.catchthenext.android.tile.TileState
import dev.catchthenext.android.tile.computeTileState
import dev.catchthenext.android.tile.groupDepartures
import dev.catchthenext.android.tile.makeFetchNetworkDeparturesBatch
import dev.catchthenext.android.tile.timeLabel
import dev.catchthenext.android.tile.updatedAtLabel
import dev.catchthenext.model.Stop
import dev.catchthenext.wear.WearGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAX_TILE_GROUPS = 3
private const val REFRESH_THRESHOLD_MS = 30_000L

@OptIn(ExperimentalHorologistApi::class)
class ClosestStopTileService : SuspendingTileService() {

    private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onDestroy() {
        super.onDestroy()
        refreshScope.cancel()
    }

    override suspend fun tileRequest(requestParams: RequestBuilders.TileRequest): TileBuilders.Tile {
        val deviceParams = requestParams.deviceConfiguration
        val dataStore = TileDataStore(this)

        val state = withContext(Dispatchers.IO) {
            val cache = dataStore.read()
            val now = System.currentTimeMillis()

            if (cache.nearbyDepartures.isEmpty()) {
                // Cold start: no cached data, block on full fetch
                doFetchState(dataStore)
            } else {
                val isStale = cache.nearbyDepartures.any { now - it.fetchedAt > REFRESH_THRESHOLD_MS }
                val favorites = WearGraph.favoritesManager(this@ClosestStopTileService).getFavorites()
                val cachedState = buildStateFromCache(cache, favorites, cache.lat, cache.lon)

                if (isStale) {
                    // Render cached immediately; refresh asynchronously and redraw when done
                    refreshScope.launch {
                        doFetchState(dataStore)
                        TileService.getUpdater(this@ClosestStopTileService)
                            .requestUpdate(ClosestStopTileService::class.java)
                    }
                }
                // Fall back to synchronous fetch only if cache couldn't produce a valid state
                cachedState ?: doFetchState(dataStore)
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

    private suspend fun doFetchState(dataStore: TileDataStore): TileState {
        val client = WearGraph.transitlandClient()
        val locationProvider = LocationProvider(this)
        val cache = dataStore.read()
        val favoritesManager = WearGraph.favoritesManager(this)
        val favorites = favoritesManager.getFavorites()

        val hasPerm = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val freshLocation = if (hasPerm) locationProvider.currentLocation() else null
        if (freshLocation != null) dataStore.updateLocation(freshLocation.lat, freshLocation.lon)

        val lat = freshLocation?.lat ?: cache.lat
        val lon = freshLocation?.lon ?: cache.lon
        val location = if (lat != null && lon != null) LatLon(lat, lon) else null
        val threshold = DistanceUnitStore(this).thresholdMetersFlow.first()

        Log.d("Departures", "doFetchState favorites=${favorites.size} hasPerm=$hasPerm loc=${location != null}")
        return computeTileState(
            favorites = favorites,
            location = location,
            hasPermission = hasPerm,
            thresholdMeters = threshold,
            fetchDeparturesBatch = makeFetchNetworkDeparturesBatch(
                { client.getDeparturesBatch(it) }, cache, favorites
            ),
            persistDepartures = { stops -> dataStore.updateNearbyDepartures(stops) },
        )
    }

    private fun buildStateFromCache(
        cache: CachedTileData,
        favorites: List<Stop>,
        lat: Double?,
        lon: Double?,
    ): TileState? {
        if (lat == null || lon == null) return null
        if (favorites.isEmpty()) return TileState.NoFavorites
        val stops = cache.nearbyDepartures.mapNotNull { cached ->
            val stop = favorites.firstOrNull { it.id == cached.stopId } ?: return@mapNotNull null
            StopWithDepartures(
                stop = stop,
                distanceMeters = haversineMeters(lat, lon, stop.lat, stop.lon),
                departures = cached.departures.filter { it.currentMinutes() >= 0 },
                fetchedAt = cached.fetchedAt,
                alerts = cached.alerts ?: emptyList(),
            )
        }
        return if (stops.isEmpty()) null
        else TileState.Ready(stops, stops.minOf { it.fetchedAt })
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
        val stopTag = if (group.showStopTag) " · ${group.stopName.take(8)}" else ""
        val routeLabel = buildString {
            append(group.routeShortName)
            if (group.headsign.isNotBlank()) append(" → ${group.headsign.take(14)}")
            append(stopTag)
        }

        val timesRow = LayoutElementBuilders.Row.Builder()
            .setWidth(DimensionBuilders.expand())
        group.times.forEachIndexed { i, time ->
            if (i > 0) {
                timesRow.addContent(
                    LayoutElementBuilders.Spacer.Builder()
                        .setWidth(DimensionBuilders.dp(6f))
                        .setHeight(DimensionBuilders.dp(1f))
                        .build()
                )
            }
            timesRow.addContent(
                Text.Builder(this, timeLabel(time.minutes))
                    .setTypography(Typography.TYPOGRAPHY_CAPTION1)
                    .setColor(ColorBuilders.argb(TileColors.departureColor(time.timeSource)))
                    .build()
            )
        }

        val routeLabelRow = if (group.hasAlert) {
            LayoutElementBuilders.Row.Builder()
                .addContent(
                    LayoutElementBuilders.Image.Builder()
                        .setResourceId(ALERT_ICON_ID)
                        .setWidth(DimensionBuilders.dp(12f))
                        .setHeight(DimensionBuilders.dp(12f))
                        .setColorFilter(LayoutElementBuilders.ColorFilter.Builder()
                            .setTint(ColorBuilders.argb(TileColors.warning))
                            .build())
                        .build()
                )
                .addContent(
                    LayoutElementBuilders.Spacer.Builder()
                        .setWidth(DimensionBuilders.dp(4f))
                        .setHeight(DimensionBuilders.dp(1f))
                        .build()
                )
                .addContent(
                    Text.Builder(this, routeLabel)
                        .setTypography(Typography.TYPOGRAPHY_BODY2)
                        .setColor(ColorBuilders.argb(TileColors.textPrimary))
                        .build()
                )
                .build()
        } else {
            Text.Builder(this, routeLabel)
                .setTypography(Typography.TYPOGRAPHY_BODY2)
                .setColor(ColorBuilders.argb(TileColors.textPrimary))
                .build()
        }

        return LayoutElementBuilders.Column.Builder()
            .setWidth(DimensionBuilders.expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_START)
            .addContent(routeLabelRow)
            .addContent(timesRow.build())
            .build()
    }

    override suspend fun resourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ResourceBuilders.Resources {
        return ResourceBuilders.Resources.Builder()
            .setVersion(requestParams.version)
            .addIdToImageMapping(
                ALERT_ICON_ID,
                ResourceBuilders.ImageResource.Builder()
                    .setAndroidResourceByResId(
                        ResourceBuilders.AndroidImageResourceByResId.Builder()
                            .setResourceId(dev.catchthenext.wear.R.drawable.ic_alert)
                            .build()
                    )
                    .build()
            )
            .build()
    }

    companion object {
        private const val ALERT_ICON_ID = "alert_icon"
    }
}
