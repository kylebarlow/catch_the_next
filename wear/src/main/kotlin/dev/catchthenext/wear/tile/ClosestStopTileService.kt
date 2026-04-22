package dev.catchthenext.wear.tile

import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material.Text
import androidx.wear.protolayout.material.layouts.PrimaryLayout
import androidx.wear.protolayout.DeviceParametersBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import com.google.android.horologist.annotations.ExperimentalHorologistApi
import com.google.android.horologist.tiles.SuspendingTileService
import dev.catchthenext.wear.storage.AndroidFavoritesManager

@OptIn(ExperimentalHorologistApi::class)
class ClosestStopTileService : SuspendingTileService() {
    
    override suspend fun tileRequest(requestParams: RequestBuilders.TileRequest): TileBuilders.Tile {
        val favoritesManager = AndroidFavoritesManager(this)
        val favorites = favoritesManager.getFavorites()
        
        val deviceParams = requestParams.deviceConfiguration
        
        val content = if (favorites.isEmpty()) {
            Text.Builder(this, "No favorites added").build()
        } else {
            Text.Builder(this, "Closest: ${favorites.first().stopName}").build()
        }
        
        return TileBuilders.Tile.Builder()
            .setTileTimeline(
                TimelineBuilders.Timeline.Builder()
                    .addTimelineEntry(
                        TimelineBuilders.TimelineEntry.Builder()
                            .setLayout(
                                LayoutElementBuilders.Layout.Builder()
                                    .setRoot(
                                        PrimaryLayout.Builder(deviceParams)
                                            .setContent(content)
                                            .build()
                                    )
                                    .build()
                            )
                            .build()
                    )
                    .build()
            )
            .build()
    }

    override suspend fun resourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ResourceBuilders.Resources {
        return ResourceBuilders.Resources.Builder().build()
    }
}
