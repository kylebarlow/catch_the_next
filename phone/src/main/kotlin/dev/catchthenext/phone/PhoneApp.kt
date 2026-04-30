package dev.catchthenext.phone

import android.app.Application
import androidx.glance.appwidget.updateAll
import dev.catchthenext.android.sync.CapabilityWatcher
import dev.catchthenext.android.sync.FavoritesSyncListener
import dev.catchthenext.android.sync.FavoritesSyncPublisher
import dev.catchthenext.android.sync.SyncMetadataStore
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

        val mgr = PhoneGraph.favoritesManager(this)
        val meta = SyncMetadataStore(this)
        FavoritesSyncPublisher.attach(this, mgr, meta)
        CapabilityWatcher.start(this, mgr, meta)
        CoroutineScope(Dispatchers.IO).launch {
            FavoritesSyncListener.coldStartReconcile(this@PhoneApp, mgr, meta)
        }
    }
}
