package dev.catchthenext.wear

import android.app.Application
import androidx.wear.tiles.TileService
import dev.catchthenext.android.storage.AndroidFavoritesManager
import dev.catchthenext.android.sync.CapabilityWatcher
import dev.catchthenext.android.sync.FavoritesSyncListener
import dev.catchthenext.android.sync.FavoritesSyncPublisher
import dev.catchthenext.android.sync.FavoritesSyncWorker
import dev.catchthenext.android.tile.DepartureWorker
import dev.catchthenext.android.tile.DeparturesRefreshCallbacks
import dev.catchthenext.wear.tile.ClosestStopTileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class WearApp : Application() {
    override fun onCreate() {
        super.onCreate()
        WearGraph.init(this)
        DepartureWorker.configure(
            getClient = { WearGraph.transitlandClient() },
            getFavorites = { ctx -> WearGraph.favoritesManager(ctx) },
        )
        DeparturesRefreshCallbacks.register { ctx ->
            TileService.getUpdater(ctx).requestUpdate(ClosestStopTileService::class.java)
        }
        DepartureWorker.schedule(this)

        val store = WearGraph.syncStateStore(this)
        FavoritesSyncPublisher.attach(this, store)
        CapabilityWatcher.start(this, store)
        FavoritesSyncWorker.configure(getStore = { ctx -> WearGraph.syncStateStore(ctx) })
        FavoritesSyncWorker.schedule(this)

        CoroutineScope(Dispatchers.IO).launch {
            val legacy = AndroidFavoritesManager(this@WearApp).getFavorites()
            store.initFromContext(this@WearApp, legacy)
            FavoritesSyncListener.coldStartReconcile(this@WearApp, store)
        }
    }
}
