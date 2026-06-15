package dev.catchthenext.android.sync

import android.content.Context

/** fdroid factory referenced by [dev.catchthenext.android.di.CommonGraph]. */
fun defaultFavoritesSyncController(): FavoritesSyncController = FdroidFavoritesSyncController

/**
 * No-op sync for the FOSS "Bay" edition. F-Droid's official repo forbids the proprietary
 * Wearable Data Layer, so there is no peer to sync with — the CRDT counters/merge in
 * [SyncState]/[SyncEngine] still operate correctly for this single device. [localNodeId]
 * returns a stable synthetic id so authored entries have a consistent author.
 */
object FdroidFavoritesSyncController : FavoritesSyncController {
    override fun attach(context: Context, store: SyncStateStore) {}
    override fun startCapabilityWatch(context: Context, store: SyncStateStore) {}
    override fun scheduleWorker(context: Context, getStore: (Context) -> SyncStateStore) {}
    override suspend fun reconcile(context: Context, store: SyncStateStore) {}
    override suspend fun localNodeId(context: Context): String? = "local"
}
