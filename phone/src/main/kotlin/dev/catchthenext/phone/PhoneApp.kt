package dev.catchthenext.phone

import android.app.Application
import dev.catchthenext.android.storage.AndroidFavoritesManager
import dev.catchthenext.android.sync.CapabilityWatcher
import dev.catchthenext.android.sync.FavoritesSyncListener
import dev.catchthenext.android.sync.FavoritesSyncPublisher
import dev.catchthenext.android.sync.FavoritesSyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class PhoneApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PhoneGraph.init(this)

        val store = PhoneGraph.syncStateStore(this)
        FavoritesSyncPublisher.attach(this, store)
        CapabilityWatcher.start(this, store)
        FavoritesSyncWorker.configure(getStore = { ctx -> PhoneGraph.syncStateStore(ctx) })
        FavoritesSyncWorker.schedule(this)

        CoroutineScope(Dispatchers.IO).launch {
            val legacy = AndroidFavoritesManager(this@PhoneApp).getFavorites()
            store.initFromContext(this@PhoneApp, legacy)
            FavoritesSyncListener.coldStartReconcile(this@PhoneApp, store)
        }
    }
}
