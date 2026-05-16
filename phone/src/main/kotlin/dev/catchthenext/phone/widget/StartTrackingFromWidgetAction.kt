package dev.catchthenext.phone.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import dev.catchthenext.phone.PhoneGraph

internal val KEY_ONESTOP_ID = ActionParameters.Key<String>("onestop_id")
internal val KEY_STOP_ID = ActionParameters.Key<Long>("stop_id")

class StartTrackingFromWidgetAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val onestopId = parameters[KEY_ONESTOP_ID]
        val stopId = parameters[KEY_STOP_ID] ?: return
        val stop = PhoneGraph.favoritesManager(context).getFavorites()
            .firstOrNull { it.id == stopId } ?: return
        PhoneGraph.liveUpdateController(context).start(stop)
    }
}
