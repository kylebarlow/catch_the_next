package dev.catchthenext.android.sync

import android.content.Context
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await

/** play factory referenced by [dev.catchthenext.android.di.CommonGraph]. */
fun defaultFavoritesSyncController(): FavoritesSyncController = PlayFavoritesSyncController

/** GMS / Wearable Data Layer backed sync, used by the Play phone edition and the Wear app. */
object PlayFavoritesSyncController : FavoritesSyncController {
    override fun attach(context: Context, store: SyncStateStore) {
        FavoritesSyncPublisher.attach(context, store)
    }

    override fun startCapabilityWatch(context: Context, store: SyncStateStore) {
        CapabilityWatcher.start(context, store)
    }

    override fun scheduleWorker(context: Context, getStore: (Context) -> SyncStateStore) {
        FavoritesSyncWorker.configure(getStore)
        FavoritesSyncWorker.schedule(context)
    }

    override suspend fun reconcile(context: Context, store: SyncStateStore) {
        FavoritesSyncListener.coldStartReconcile(context, store)
    }

    override suspend fun localNodeId(context: Context): String? =
        runCatching { Wearable.getNodeClient(context).localNode.await().id }.getOrNull()
}
