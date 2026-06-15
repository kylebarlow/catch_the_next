package dev.catchthenext.wear

import android.app.Application
import dev.catchthenext.android.storage.AndroidFavoritesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class WearApp : Application() {
    override fun onCreate() {
        super.onCreate()
        WearGraph.init(this)

        val store = WearGraph.syncStateStore(this)
        val sync = WearGraph.favoritesSyncController()
        sync.attach(this, store)
        sync.startCapabilityWatch(this, store)
        sync.scheduleWorker(this) { ctx -> WearGraph.syncStateStore(ctx) }

        CoroutineScope(Dispatchers.IO).launch {
            val legacy = AndroidFavoritesManager(this@WearApp).getFavorites()
            store.initFromContext({ sync.localNodeId(this@WearApp) }, legacy)
            sync.reconcile(this@WearApp, store)
        }
    }
}
