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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

@OptIn(ExperimentalHorologistApi::class)
class ClosestStopTileService : SuspendingTileService() {

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
        // applicationContext throughout: the refresh outlives this service instance (see
        // [TileRefresher]), so nothing here may touch the possibly-destroyed service.
        val appContext = applicationContext
        TileRefresher.scope.launch {
            val dataStore = TileDataStore(appContext)
            val cache = dataStore.read()
            val now = System.currentTimeMillis()
            val stale = force || cache.nearbyDepartures.isEmpty() ||
                cache.nearbyDepartures.any { now - it.fetchedAt > Tuning.TILE_REFRESH_THRESHOLD_MS }
            if (!stale || !TileRefresher.inFlight.compareAndSet(false, true)) return@launch
            try {
                Log.d("Departures", "tile refresh ($reason)")
                val requestUpdate = {
                    TileService.getUpdater(appContext)
                        .requestUpdate(ClosestStopTileService::class.java)
                }
                // Render as soon as the departures for the location we already had land; the
                // parallel fresh fix only earns a second render if it changed the selection.
                val intermediate = AtomicReference<TileState?>(null)
                val state = departuresPipeline(
                    appContext,
                    WearGraph.transitlandClient(),
                    WearGraph.favoritesManager(appContext),
                    dataStore,
                    gpsFallback = true,
                ).computeState(forceFresh = false) { s ->
                    intermediate.set(s)
                    requestUpdate()
                }
                if (state !== intermediate.get()) requestUpdate()
            } finally {
                TileRefresher.inFlight.set(false)
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

        // Everything below is sized from the device: see [TileFit].
        val fit = TileFit.of(deviceParams)
        val timeline = TimelineBuilders.Timeline.Builder()
        if (state is TileState.Ready) {
            // Slice only on the departures this device will actually show; a boundary for an
            // invisible fourth group would just re-render an identical tile.
            val maxGroups = fit.maxGroups(hasHeader = showsStopHeader(state.stops))
            buildTimelineSlices(state, System.currentTimeMillis(), maxGroups = maxGroups).forEach { slice ->
                timeline.addTimelineEntry(sliceEntry(slice, state.fetchedAt, fit, deviceParams))
            }
        } else {
            val layout = state?.let { renderLayout(it, fit, deviceParams) }
                ?: simpleLayout(fit, "Loading", "departures…")
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
        fit: TileFit,
        deviceParams: DeviceParameters,
    ): TimelineBuilders.TimelineEntry {
        val hasHeader = showsStopHeader(slice.stops)
        val groups = groupDepartures(slice.stops, filter = tileDepartureFilter).take(fit.maxGroups(hasHeader))
        val layout = if (groups.isEmpty()) {
            simpleLayout(fit, "No departures", "in the next hour", footer = updatedAtLabel(fetchedAt))
        } else {
            readyLayout(groups, slice.stops, fetchedAt, fit, deviceParams)
        }
        return entryFor(layout, slice.validUntilMillis)
    }

    /** The stop name is shown as a header only when every row belongs to the same stop. */
    private fun showsStopHeader(stops: List<StopWithDepartures>): Boolean = stops.size == 1

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

    private fun renderLayout(state: TileState, fit: TileFit, deviceParams: DeviceParameters): LayoutElement =
        when (state) {
            is TileState.NoFavorites -> simpleLayout(fit, "Add favorites", "in app")
            is TileState.NoPermission -> simpleLayout(fit, "Open app to", "grant location")
            is TileState.NoLocation -> simpleLayout(fit, "Getting location…")
            is TileState.NetworkError -> simpleLayout(fit, "Network error")
            is TileState.Ready -> readyLayout(
                groupDepartures(state.stops, filter = tileDepartureFilter)
                    .take(fit.maxGroups(hasHeader = showsStopHeader(state.stops))),
                state.stops,
                state.fetchedAt,
                fit,
                deviceParams,
            )
        }

    /**
     * Root of every tile layout: a content band centred on the screen and a footer pinned to the
     * bottom edge, as siblings in one Box so the footer's position never depends on how tall the
     * content turned out. [TileFit] sizes the band so the content it budgets for cannot reach
     * the footer.
     */
    private fun rootLayout(fit: TileFit, content: LayoutElement, footer: String?): LayoutElement {
        val root = LayoutElementBuilders.Box.Builder()
            .setWidth(DimensionBuilders.expand())
            .setHeight(DimensionBuilders.expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .addContent(
                LayoutElementBuilders.Box.Builder()
                    .setWidth(DimensionBuilders.dp(fit.contentWidthDp))
                    .setHeight(DimensionBuilders.dp(fit.contentHeightDp))
                    .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                    .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                    .addContent(content)
                    .build()
            )
        if (footer != null) {
            root.addContent(
                LayoutElementBuilders.Box.Builder()
                    .setWidth(DimensionBuilders.expand())
                    .setHeight(DimensionBuilders.expand())
                    .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                    .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_BOTTOM)
                    .setModifiers(
                        ModifiersBuilders.Modifiers.Builder()
                            .setPadding(
                                ModifiersBuilders.Padding.Builder()
                                    .setBottom(DimensionBuilders.dp(fit.footerBottomInsetDp))
                                    .build()
                            )
                            .build()
                    )
                    .addContent(
                        Text.Builder(this, footer)
                            .setTypography(Typography.TYPOGRAPHY_CAPTION3)
                            .setColor(ColorBuilders.argb(TileColors.textDim))
                            .build()
                    )
                    .build()
            )
        }
        return root.build()
    }

    private fun simpleLayout(fit: TileFit, vararg lines: String, footer: String? = null): LayoutElement {
        val col = LayoutElementBuilders.Column.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
        lines.forEach { line ->
            col.addContent(
                Text.Builder(this, line)
                    .setTypography(Typography.TYPOGRAPHY_CAPTION1)
                    .setColor(ColorBuilders.argb(TileColors.textPrimary))
                    .build()
            )
        }
        return rootLayout(fit, col.build(), footer)
    }

    private fun readyLayout(
        groups: List<GroupedDeparture>,
        stops: List<StopWithDepartures>,
        fetchedAt: Long,
        fit: TileFit,
        deviceParams: DeviceParameters,
    ): LayoutElement {
        val col = LayoutElementBuilders.Column.Builder()
            .setWidth(DimensionBuilders.expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_START)

        if (showsStopHeader(stops)) {
            col.addContent(
                LayoutElementBuilders.Box.Builder()
                    .setWidth(DimensionBuilders.expand())
                    .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                    .addContent(
                        Text.Builder(this, stops[0].stop.stopName.take(fit.headerChars))
                            .setTypography(Typography.TYPOGRAPHY_CAPTION1)
                            .setColor(ColorBuilders.argb(TileColors.textDim))
                            .build()
                    )
                    .build()
            )
            col.addContent(verticalSpacer(TileFit.HEADER_GAP_DP))
        }

        groups.forEachIndexed { i, group ->
            if (i > 0) col.addContent(verticalSpacer(fit.groupSpacerDp))
            col.addContent(groupedDepartureRow(group, fit, deviceParams))
        }

        return rootLayout(fit, col.build(), footer = updatedAtLabel(fetchedAt))
    }

    private fun verticalSpacer(heightDp: Float): LayoutElement =
        LayoutElementBuilders.Spacer.Builder()
            .setWidth(DimensionBuilders.expand())
            .setHeight(DimensionBuilders.dp(heightDp))
            .build()

    private fun groupedDepartureRow(
        group: GroupedDeparture,
        fit: TileFit,
        deviceParams: DeviceParameters,
    ): LayoutElement {
        val routeLabel = fit.routeLabel(group.routeShortName, group.headsign, group.hasAlert)

        val timesRow = LayoutElementBuilders.Row.Builder()
            .setWidth(DimensionBuilders.expand())
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
        group.times.forEachIndexed { i, time ->
            if (i > 0) timesRow.addContent(horizontalSpacer(6f))
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
        if (group.showStopTag) {
            // The stop tag rides on the times row, which always has spare width, so the route
            // label row is free for the headsign. Last in the row, so it ellipsises rather than
            // the times (Material Text is single-line, ellipsis-end by default).
            timesRow.addContent(horizontalSpacer(8f))
            timesRow.addContent(
                Text.Builder(this, group.stopName.take(STOP_TAG_MAX_CHARS))
                    .setTypography(Typography.TYPOGRAPHY_CAPTION2)
                    .setColor(ColorBuilders.argb(TileColors.textDim))
                    .build()
            )
        }

        val routeLabelText = Text.Builder(this, routeLabel)
            .setTypography(Typography.TYPOGRAPHY_BODY2)
            .setColor(ColorBuilders.argb(TileColors.textPrimary))
            .build()
        val routeLabelRow = if (group.hasAlert) {
            LayoutElementBuilders.Row.Builder()
                .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                .addContent(
                    LayoutElementBuilders.Image.Builder()
                        .setResourceId(ALERT_ICON_ID)
                        .setWidth(DimensionBuilders.dp(TileFit.ALERT_ICON_DP))
                        .setHeight(DimensionBuilders.dp(TileFit.ALERT_ICON_DP))
                        .setColorFilter(LayoutElementBuilders.ColorFilter.Builder()
                            .setTint(ColorBuilders.argb(TileColors.warning))
                            .build())
                        .build()
                )
                .addContent(horizontalSpacer(TileFit.ALERT_GAP_DP))
                .addContent(routeLabelText)
                .build()
        } else {
            routeLabelText
        }

        return LayoutElementBuilders.Column.Builder()
            .setWidth(DimensionBuilders.expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_START)
            .addContent(routeLabelRow)
            .addContent(timesRow.build())
            .build()
    }

    private fun horizontalSpacer(widthDp: Float): LayoutElement =
        LayoutElementBuilders.Spacer.Builder()
            .setWidth(DimensionBuilders.dp(widthDp))
            .setHeight(DimensionBuilders.dp(1f))
            .build()

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

        /** Upper bound on the stop tag; the renderer ellipsises it further if the row is short. */
        private const val STOP_TAG_MAX_CHARS = 12
    }
}
