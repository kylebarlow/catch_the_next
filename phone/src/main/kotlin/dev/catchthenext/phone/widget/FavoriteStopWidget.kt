package dev.catchthenext.phone.widget

import android.content.Context
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceTheme
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import dev.catchthenext.phone.PhoneGraph

private val PREF_ONESTOP_ID = stringPreferencesKey("onestop_id")
private val PREF_STOP_ID = longPreferencesKey("stop_id")
private val PREF_STOP_NAME = stringPreferencesKey("stop_name")

/** Home-screen widget for one configured favorite: next departure times + tap-to-track. */
class FavoriteStopWidget : GlanceAppWidget() {

    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // Data is loaded before provideContent: provideGlance re-runs on every update()
        // (worker refresh, tap refresh, post-configure), so the content stays current.
        val prefs = getAppWidgetState(context, PreferencesGlanceStateDefinition, id)
        val stopId = prefs[PREF_STOP_ID]
        val stop = stopId?.let { sid ->
            PhoneGraph.favoritesManager(context).getFavorites().firstOrNull { it.id == sid }
        }
        val swd = stop?.let { loadWidgetDepartures(context, it) }

        provideContent {
            GlanceTheme {
                DepartureWidgetContent(
                    swd = swd,
                    emptyMessage = if (stopId == null) "Tap to configure" else "Stop not in favorites",
                    onTap = actionRunCallback<StartTrackingFromWidgetAction>(
                        actionParametersOf(
                            KEY_ONESTOP_ID to (prefs[PREF_ONESTOP_ID] ?: ""),
                            KEY_STOP_ID to (stopId ?: -1L),
                        )
                    ),
                    onRefresh = actionRunCallback<RefreshFavoriteStopWidgetAction>(),
                )
            }
        }
    }

    companion object {
        suspend fun configure(
            context: Context,
            glanceId: GlanceId,
            onestopId: String?,
            stopId: Long,
            stopName: String,
        ) {
            updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { prefs ->
                prefs.toMutablePreferences().apply {
                    if (onestopId != null) set(PREF_ONESTOP_ID, onestopId)
                    set(PREF_STOP_ID, stopId)
                    set(PREF_STOP_NAME, stopName)
                }
            }
        }
    }
}

class RefreshFavoriteStopWidgetAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        FavoriteStopWidget().update(context, glanceId)
    }
}

class FavoriteStopWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = FavoriteStopWidget()
}
