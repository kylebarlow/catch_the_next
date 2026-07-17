package dev.catchthenext.phone

import android.app.Application
import dev.catchthenext.android.storage.AndroidFavoritesManager
import dev.catchthenext.phone.widget.WidgetRefreshWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class PhoneApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PhoneGraph.init(this)

        val store = PhoneGraph.syncStateStore(this)
        val sync = PhoneGraph.favoritesSyncController()
        sync.attach(this, store)
        sync.startCapabilityWatch(this, store)
        sync.scheduleWorker(this) { ctx -> PhoneGraph.syncStateStore(ctx) }
        WidgetRefreshWorker.schedule(this)

        CoroutineScope(Dispatchers.IO).launch {
            val legacy = AndroidFavoritesManager(this@PhoneApp).getFavorites()
            store.initFromContext({ sync.localNodeId(this@PhoneApp) }, legacy)
            sync.reconcile(this@PhoneApp, store)
        }
    }
}
