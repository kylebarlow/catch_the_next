package dev.catchthenext.android.sync

import android.content.Context

/**
 * Flavor seam for watch↔phone favorites sync. The `play` variant backs this with the Wearable
 * Data Layer (GMS); the `fdroid` variant supplies an all-no-op implementation so the FOSS build
 * carries zero proprietary dependencies. Code in `main`/UI references only this interface.
 *
 * Each flavor source set provides a top-level [defaultFavoritesSyncController] factory in this
 * package; [dev.catchthenext.android.di.CommonGraph] resolves it once.
 */
interface FavoritesSyncController {
    /** Start publishing local favorites changes to the peer. */
    fun attach(context: Context, store: SyncStateStore)

    /** Watch peer reachability and reconcile when a peer appears. */
    fun startCapabilityWatch(context: Context, store: SyncStateStore)

    /** Schedule the periodic background reconcile/republish worker. */
    fun scheduleWorker(context: Context, getStore: (Context) -> SyncStateStore)

    /** Pull the peer's state and merge it into [store]. No-op when there is no peer. */
    suspend fun reconcile(context: Context, store: SyncStateStore)

    /** The local node id used to author CRDT entries. Stable synthetic id on fdroid. */
    suspend fun localNodeId(context: Context): String?
}
