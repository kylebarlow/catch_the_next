package dev.catchthenext.phone

import android.app.Application
import androidx.glance.appwidget.updateAll
import dev.catchthenext.android.tile.DepartureWorker
import dev.catchthenext.android.tile.DeparturesRefreshCallbacks
import dev.catchthenext.phone.widget.DeparturesWidget

class PhoneApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DepartureWorker.configure(
            getClient = { PhoneGraph.transitlandClient() },
            getFavorites = { ctx -> PhoneGraph.favoritesManager(ctx) },
        )
        DeparturesRefreshCallbacks.register { ctx ->
            DeparturesWidget().updateAll(ctx)
        }
        DepartureWorker.schedule(this)
    }
}
