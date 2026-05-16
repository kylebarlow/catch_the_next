package dev.catchthenext.phone.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider

private const val PREF_ONESTOP_ID = "onestop_id"
private const val PREF_STOP_ID = "stop_id"
private const val PREF_STOP_NAME = "stop_name"

class FavoriteStopWidget : GlanceAppWidget() {

    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { Content() }
    }

    @Composable
    private fun Content() {
        val prefs = currentState<androidx.datastore.preferences.core.Preferences>()
        val stopName = prefs[androidx.datastore.preferences.core.stringPreferencesKey(PREF_STOP_NAME)] ?: "Tap to configure"
        val onestopId = prefs[androidx.datastore.preferences.core.stringPreferencesKey(PREF_ONESTOP_ID)]
        val stopId = prefs[androidx.datastore.preferences.core.longPreferencesKey(PREF_STOP_ID)] ?: -1L

        val action = if (stopId != -1L) {
            actionRunCallback<StartTrackingFromWidgetAction>(
                actionParametersOf(
                    KEY_ONESTOP_ID to (onestopId ?: ""),
                    KEY_STOP_ID to stopId,
                )
            )
        } else {
            actionRunCallback<StartTrackingFromWidgetAction>(actionParametersOf())
        }

        GlanceTheme {
            Box(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(GlanceTheme.colors.surface)
                    .padding(8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "🚌",
                        style = TextStyle(fontSize = 18.sp),
                        modifier = GlanceModifier.padding(bottom = 2.dp),
                    )
                    Text(
                        stopName,
                        style = TextStyle(
                            color = GlanceTheme.colors.onSurface,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                        maxLines = 2,
                    )
                }
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
                    if (onestopId != null) set(androidx.datastore.preferences.core.stringPreferencesKey(PREF_ONESTOP_ID), onestopId)
                    set(androidx.datastore.preferences.core.longPreferencesKey(PREF_STOP_ID), stopId)
                    set(androidx.datastore.preferences.core.stringPreferencesKey(PREF_STOP_NAME), stopName)
                }
            }
        }
    }
}

class FavoriteStopWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = FavoriteStopWidget()
}
