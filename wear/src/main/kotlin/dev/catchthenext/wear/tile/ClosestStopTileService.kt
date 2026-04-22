package dev.catchthenext.wear.tile

import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.material.Text
import androidx.wear.protolayout.material.layouts.PrimaryLayout
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import com.google.android.horologist.tiles.CoroutinesTileService
import dev.catchthenext.wear.storage.AndroidFavoritesManager

class ClosestStopTileService : CoroutinesTileService() {
    
    override suspend fun tileRequest(requestParams: RequestBuilders.TileRequest): TileBuilders.Tile {
        val favoritesManager = AndroidFavoritesManager(this)
        val favorites = favoritesManager.getFavorites()
        
        val content = if (favorites.isEmpty()) {
            Text.Builder(this, "No favorites added").build()
        } else {
            Text.Builder(this, "Closest: ${favorites.first().stopName}").build()
        }
        
        return TileBuilders.Tile.Builder()
            .setTileTimeline(
                TileBuilders.Timeline.Builder()
                    .addTimelineEntry(
                        TileBuilders.TimelineEntry.Builder()
                            .setLayout(
                                LayoutElementBuilders.Layout.Builder()
                                    .setRoot(
                                        PrimaryLayout.Builder(this)
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

    override suspend fun resourcesRequest(requestParams: RequestBuilders.ResourcesRequest): TileBuilders.Resources {
        return TileBuilders.Resources.Builder().build()
    }
}
