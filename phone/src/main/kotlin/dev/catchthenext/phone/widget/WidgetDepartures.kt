package dev.catchthenext.phone.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import dev.catchthenext.android.tile.StopWithDepartures
import dev.catchthenext.android.tile.TileDataStore
import dev.catchthenext.android.tile.departureColorArgb
import dev.catchthenext.android.tile.freshnessLabel
import dev.catchthenext.android.tile.groupDepartures
import dev.catchthenext.android.tile.makeFetchNetworkDeparturesBatch
import dev.catchthenext.android.tile.timeLabel
import dev.catchthenext.model.Stop
import dev.catchthenext.phone.PhoneGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Fetches departures for one favorite stop for widget display: network with the shared
 * per-stop cache TTL, falling back to the tile cache when offline. Null when nothing is
 * available at all.
 */
suspend fun loadWidgetDepartures(context: Context, stop: Stop): StopWithDepartures? =
    withContext(Dispatchers.IO) {
        val cache = TileDataStore(context).read()
        val fetch = makeFetchNetworkDeparturesBatch(
            getDeparturesBatch = { ids -> PhoneGraph.transitlandClient().getDeparturesBatch(ids) },
            cache = cache,
            stops = listOf(stop),
        )
        runCatching {
            fetch(listOf(stop.id))[stop.id]?.let { f ->
                StopWithDepartures(
                    stop = stop,
                    distanceMeters = 0.0,
                    departures = f.departures.filter { it.currentMinutes() >= 0 },
                    fetchedAt = f.fetchedAt,
                    alerts = f.alerts,
                    isStale = f.isStale,
                )
            }
        }.getOrNull()
    }

/**
 * Shared widget body: stop name, next departures per route (respecting the favorite's
 * route filter via [groupDepartures]), and a staleness label. [onTap] covers the whole
 * widget (start Live Update tracking); [onRefresh] is a small corner affordance.
 */
@Composable
fun DepartureWidgetContent(
    swd: StopWithDepartures?,
    emptyMessage: String,
    onTap: Action,
    onRefresh: Action,
) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.surface)
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .clickable(onTap),
    ) {
        if (swd == null) {
            Text(
                emptyMessage,
                style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 12.sp),
            )
            return@Column
        }
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                swd.stop.displayName,
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                ),
                maxLines = 1,
                modifier = GlanceModifier.defaultWeight(),
            )
            Text(
                "↻",
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 14.sp),
                modifier = GlanceModifier.padding(start = 6.dp).clickable(onRefresh),
            )
        }
        val groups = groupDepartures(listOf(swd), maxPerGroup = 3).take(3)
        if (groups.isEmpty()) {
            Text(
                "No upcoming departures",
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp),
                modifier = GlanceModifier.padding(top = 4.dp),
            )
        } else {
            groups.forEach { group ->
                Row(
                    modifier = GlanceModifier.fillMaxWidth().padding(top = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        group.routeShortName,
                        style = TextStyle(
                            color = GlanceTheme.colors.onSurface,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                        maxLines = 1,
                    )
                    Spacer(GlanceModifier.width(6.dp))
                    group.times.take(3).forEachIndexed { i, time ->
                        if (i > 0) Spacer(GlanceModifier.width(6.dp))
                        Text(
                            timeLabel(time.minutes),
                            style = TextStyle(
                                color = ColorProvider(Color(departureColorArgb(time.timeSource))),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                            ),
                        )
                    }
                }
            }
        }
        Text(
            freshnessLabel(swd.fetchedAt),
            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 9.sp),
            modifier = GlanceModifier.padding(top = 4.dp),
        )
    }
}
