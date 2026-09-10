package dev.catchthenext.wear.tile

import android.util.Log
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
import androidx.wear.tiles.EventBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.android.horologist.annotations.ExperimentalHorologistApi
import com.google.android.horologist.tiles.SuspendingTileService
import dev.catchthenext.android.location.haversineMeters
import dev.catchthenext.android.tile.CachedTileData
import dev.catchthenext.android.tile.GroupedDeparture
import dev.catchthenext.android.tile.StopWithDepartures
import dev.catchthenext.android.tile.TileDataStore
import dev.catchthenext.android.tile.TileState
import dev.catchthenext.android.tile.TimelineSlice
import dev.catchthenext.android.tile.Tuning
import dev.catchthenext.android.tile.buildTimelineSlices
import dev.catchthenext.android.tile.departuresPipeline
import dev.catchthenext.android.tile.groupDepartures
import dev.catchthenext.android.tile.tileDepartureFilter
import dev.catchthenext.android.tile.timeLabel
import dev.catchthenext.android.tile.updatedAtLabel
import dev.catchthenext.model.Stop
import dev.catchthenext.wear.WearGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalHorologistApi::class)
class ClosestStopTileService : SuspendingTileService() {

    private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // The enter event and the tile request can arrive together; the pipeline (and its location
    // lookup) must only run once. TransitlandClient.dedupe only collapses the HTTP call.
    private val refreshInFlight = AtomicBoolean(false)

    override fun onDestroy() {
        super.onDestroy()
        refreshScope.cancel()
    }

    /**
     * Fires when the user swipes to the tile — earlier than [tileRequest], so the fetch overlaps
     * the swipe animation instead of starting after it. Deprecated from Tiles 1.5 and inert for
     * apps targeting API 36; migrate to `onRecentInteractionEvents` before bumping targetSdk.
     */
    @Deprecated("Replaced by onRecentInteractionEvents in Tiles 1.5; see docs/wear-tile-client-plan.md §11")
    override fun onTileEnterEvent(requestParams: EventBuilders.TileEnterEvent) {
        @Suppress("DEPRECATION")
        super.onTileEnterEvent(requestParams)
        refreshIfStale("enter")
    }

    /**
     * Fetches in the background and asks for a re-render, unless the cache is still fresh.
     * [force] skips the freshness check for callers that already know the cache is unusable.
     */
    private fun refreshIfStale(reason: String, force: Boolean = false) {
        refreshScope.launch {
            val dataStore = TileDataStore(this@ClosestStopTileService)
            val cache = dataStore.read()
            val now = System.currentTimeMillis()
            val stale = force || cache.nearbyDepartures.isEmpty() ||
                cache.nearbyDepartures.any { now - it.fetchedAt > Tuning.TILE_REFRESH_THRESHOLD_MS }
            if (!stale || !refreshInFlight.compareAndSet(false, true)) return@launch
            try {
                Log.d("Departures", "tile refresh ($reason)")
                doFetchState(dataStore)
                TileService.getUpdater(this@ClosestStopTileService)
                    .requestUpdate(ClosestStopTileService::class.java)
            } finally {
                refreshInFlight.set(false)
            }
        }
    }

    override suspend fun tileRequest(requestParams: RequestBuilders.TileRequest): TileBuilders.Tile {
        val deviceParams = requestParams.deviceConfiguration
        val dataStore = TileDataStore(this)

        // Never block on the network here: render from cache (or a loading placeholder on a cold
        // cache) and let refreshIfStale requestUpdate once the fetch lands.
        val state = withContext(Dispatchers.IO) {
            val cache = dataStore.read()
            if (cache.nearbyDepartures.isEmpty()) {
                null
            } else {
                val favorites = WearGraph.favoritesManager(this@ClosestStopTileService).getFavorites()
                buildStateFromCache(cache, favorites, cache.lat, cache.lon)
            }
        }
        // A null state means the cache could not produce a layout (no stored location yet), so
        // the fetch has to happen regardless of how recently the departures were written.
        refreshIfStale("tileRequest", force = state == null)

        val timeline = TimelineBuilders.Timeline.Builder()
        if (state is TileState.Ready) {
            buildTimelineSlices(state, System.currentTimeMillis()).forEach { slice ->
                timeline.addTimelineEntry(sliceEntry(slice, state.fetchedAt, deviceParams))
            }
        } else {
            val layout = state?.let { renderLayout(it, deviceParams) }
                ?: simpleLayout(deviceParams, "Loading", "departures…")
            timeline.addTimelineEntry(entryFor(layout, validUntilMillis = null))
        }

        return TileBuilders.Tile.Builder()
            .setFreshnessIntervalMillis(Tuning.TILE_FRESHNESS_INTERVAL_MS)
            .setTileTimeline(timeline.build())
            .build()
    }

    private fun sliceEntry(
        slice: TimelineSlice,
        fetchedAt: Long,
        deviceParams: DeviceParameters,
    ): TimelineBuilders.TimelineEntry {
        val groups = groupDepartures(slice.stops, filter = tileDepartureFilter).take(Tuning.TILE_MAX_GROUPS)
        val layout = if (groups.isEmpty()) {
            simpleLayout(deviceParams, "No departures", "in the next hour")
        } else {
            readyLayout(groups, slice.stops, fetchedAt, deviceParams)
        }
        return entryFor(layout, slice.validUntilMillis)
    }

    private fun entryFor(layout: LayoutElement, validUntilMillis: Long?): TimelineBuilders.TimelineEntry {
        val entry = TimelineBuilders.TimelineEntry.Builder()
            .setLayout(
                LayoutElementBuilders.Layout.Builder()
                    .setRoot(tappableLayout(layout))
                    .build()
            )
        // Only the end matters: every slice is valid from now, and the renderer picks whichever
        // entry has the shortest remaining validity — i.e. the earliest-expiring slice.
        validUntilMillis?.let {
            entry.setValidity(TimelineBuilders.TimeInterval.Builder().setEndMillis(it).build())
        }
        return entry.build()
    }

    private suspend fun doFetchState(dataStore: TileDataStore): TileState =
        departuresPipeline(
            this,
            WearGraph.transitlandClient(),
            WearGraph.favoritesManager(this),
            dataStore,
        ).computeState(forceFresh = false)

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
            is TileState.Ready -> readyLayout(
                groupDepartures(state.stops, filter = tileDepartureFilter).take(Tuning.TILE_MAX_GROUPS),
                state.stops,
                state.fetchedAt,
                deviceParams,
            )
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

    private fun readyLayout(
        groups: List<GroupedDeparture>,
        stops: List<StopWithDepartures>,
        fetchedAt: Long,
        deviceParams: DeviceParameters,
    ): LayoutElement {
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
            col.addContent(groupedDepartureRow(group, deviceParams))
        }

        val builder = PrimaryLayout.Builder(deviceParams)
        if (stops.size == 1) {
            builder.setPrimaryLabelTextContent(
                Text.Builder(this, stops[0].stop.stopName.take(22))
                    .setTypography(Typography.TYPOGRAPHY_CAPTION1)
                    .setColor(ColorBuilders.argb(TileColors.textDim))
                    .build()
            )
        }
        return builder
            .setContent(col.build())
            .setSecondaryLabelTextContent(
                Text.Builder(this, updatedAtLabel(fetchedAt))
                    .setTypography(Typography.TYPOGRAPHY_CAPTION3)
                    .setColor(ColorBuilders.argb(TileColors.textDim))
                    .build()
            )
            .build()
    }

    private fun groupedDepartureRow(group: GroupedDeparture, deviceParams: DeviceParameters): LayoutElement {
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
            // Dynamic countdown so "5m" ticks down between tile requests; static on old renderers.
            val text = if (supportsDynamicExpressions(deviceParams)) {
                Text.Builder(
                    this,
                    countdownStringProp(time.departureEpochMillis, time.minutes),
                    COUNTDOWN_LAYOUT_CONSTRAINT,
                )
            } else {
                Text.Builder(this, timeLabel(time.minutes))
            }
            timesRow.addContent(
                text
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
