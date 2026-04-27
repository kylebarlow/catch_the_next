package dev.catchthenext.wear

import android.app.Application
import androidx.wear.tiles.TileService
import dev.catchthenext.android.sync.CapabilityWatcher
import dev.catchthenext.android.sync.FavoritesSyncListener
import dev.catchthenext.android.sync.FavoritesSyncPublisher
import dev.catchthenext.android.sync.SyncMetadataStore
import dev.catchthenext.android.tile.DepartureWorker
import dev.catchthenext.android.tile.DeparturesRefreshCallbacks
import dev.catchthenext.wear.tile.ClosestStopTileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
        DepartureWorker.schedule(this)

        val mgr = WearGraph.favoritesManager(this)
        val meta = SyncMetadataStore(this)
        FavoritesSyncPublisher.attach(this, mgr, meta)
        CapabilityWatcher.start(this, mgr, meta)
        CoroutineScope(Dispatchers.IO).launch {
            FavoritesSyncListener.coldStartReconcile(this@WearApp, mgr, meta)
        }
    }
}
