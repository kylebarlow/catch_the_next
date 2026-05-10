package dev.catchthenext.phone

import android.app.Application
import androidx.glance.appwidget.updateAll
import dev.catchthenext.android.storage.AndroidFavoritesManager
import dev.catchthenext.android.sync.CapabilityWatcher
import dev.catchthenext.android.sync.FavoritesSyncListener
import dev.catchthenext.android.sync.FavoritesSyncPublisher
import dev.catchthenext.android.sync.FavoritesSyncWorker
import dev.catchthenext.android.tile.DepartureWorker
import dev.catchthenext.android.tile.DeparturesRefreshCallbacks
import dev.catchthenext.phone.widget.DeparturesWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class PhoneApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PhoneGraph.init(this)
        DepartureWorker.configure(
            getClient = { PhoneGraph.transitlandClient() },
            getFavorites = { ctx -> PhoneGraph.favoritesManager(ctx) },
        )
        DeparturesRefreshCallbacks.register { ctx ->
            DeparturesWidget().updateAll(ctx)
        }
        DepartureWorker.schedule(this)

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
