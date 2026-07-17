package dev.catchthenext.phone.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.GlanceTheme
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.provideContent
import dev.catchthenext.android.tile.TileState
import dev.catchthenext.android.tile.departuresPipeline
import dev.catchthenext.phone.PhoneGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Home-screen twin of the wear tile: the closest favorite's next departures.
 * Tap starts Live Update tracking for that stop.
 */
class ClosestFavoriteWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val state = withContext(Dispatchers.IO) {
            val pipeline = departuresPipeline(
                context,
                PhoneGraph.transitlandClient(),
                PhoneGraph.favoritesManager(context),
            )
            runCatching { pipeline.computeState(forceFresh = false) }
                .getOrElse { runCatching { pipeline.quickCacheRead() }.getOrNull() }
        }
        val swd = (state as? TileState.Ready)?.stops?.minByOrNull { it.distanceMeters }
        val emptyMessage = when (state) {
            is TileState.NoFavorites -> "No favorites yet"
            is TileState.NoPermission -> "Location permission needed"
            is TileState.NoLocation -> "No location"
            is TileState.NetworkError -> "Offline — no cached departures"
            else -> "No departures"
        }

        provideContent {
            GlanceTheme {
                DepartureWidgetContent(
                    swd = swd,
                    emptyMessage = emptyMessage,
                    onTap = actionRunCallback<StartClosestTrackingAction>(actionParametersOf()),
                    onRefresh = actionRunCallback<RefreshClosestFavoriteWidgetAction>(),
                )
            }
        }
    }
}

class RefreshClosestFavoriteWidgetAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        ClosestFavoriteWidget().update(context, glanceId)
    }
}

class ClosestFavoriteWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = ClosestFavoriteWidget()
}
