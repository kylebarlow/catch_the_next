package dev.catchthenext.wear

import android.app.Application
import androidx.wear.tiles.TileService
import dev.catchthenext.android.tile.DepartureWorker
import dev.catchthenext.android.tile.DeparturesRefreshCallbacks
import dev.catchthenext.wear.tile.ClosestStopTileService

class WearApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DepartureWorker.configure(
            getClient = { WearGraph.transitlandClient() },
            getFavorites = { ctx -> WearGraph.favoritesManager(ctx) },
        )
        DeparturesRefreshCallbacks.register { ctx ->
            TileService.getUpdater(ctx).requestUpdate(ClosestStopTileService::class.java)
        }
    }
}
