package dev.catchthenext.phone.widget

import android.content.Context
import android.widget.Toast
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import dev.catchthenext.android.location.LocationMode
import dev.catchthenext.android.location.LocationProvider
import dev.catchthenext.android.location.withinMeters
import dev.catchthenext.android.storage.DistanceUnitStore
import dev.catchthenext.phone.PhoneGraph
import kotlinx.coroutines.flow.first

class StartClosestTrackingAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val location = LocationProvider(context).locate(LocationMode.HIGH)
        if (location == null) {
            Toast.makeText(context, "Could not get location", Toast.LENGTH_SHORT).show()
            return
        }
        val threshold = DistanceUnitStore(context).thresholdMetersFlow.first()
        val favorites = PhoneGraph.favoritesManager(context).getFavorites()
        val closest = favorites.withinMeters(location.lat, location.lon, threshold).firstOrNull()
        if (closest == null) {
            Toast.makeText(context, "No stops in range", Toast.LENGTH_SHORT).show()
            return
        }
        PhoneGraph.liveUpdateController(context).start(closest.first)
    }
}
