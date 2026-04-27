package dev.catchthenext.android.sync

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import dev.catchthenext.storage.FavoritesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.tasks.await

private const val TAG = "FavSync"

private const val KEY_PAYLOAD = "payload"
private const val KEY_UPDATED_AT = "updatedAt"
private const val KEY_VERSION = "version"

// Last-write-wins conflict resolution limitation:
// If both phone and watch edit favorites while disconnected, the side that syncs LAST wins.
// The other side's additions or removals are silently dropped. This is acceptable for a transit
// favorites app where simultaneous offline edits are rare and the data set is small. A proper
// CRDT (OR-Set tombstone model) would avoid this but adds significant schema complexity.
abstract class FavoritesSyncListener : WearableListenerService() {
    protected abstract fun favoritesManager(): FavoritesManager
    protected abstract fun metaStore(): SyncMetadataStore

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onDataChanged(events: DataEventBuffer) {
        Log.d(TAG, "onDataChanged fired")
        events.use { buffer ->
            for (event in buffer) {
                Log.d(TAG, "event type=${event.type} path=${event.dataItem.uri.path}")
                if (event.type == DataEvent.TYPE_CHANGED &&
                    event.dataItem.uri.path == PATH_FAVORITES
                ) {
                    val map = DataMapItem.fromDataItem(event.dataItem).dataMap
                    val json = map.getString(KEY_PAYLOAD) ?: continue
                    val remoteVersion = map.getLong(KEY_VERSION)
                    val remoteUpdatedAt = map.getLong(KEY_UPDATED_AT)
                    // uri.host is the publisher's node ID — free from the Data Layer without
                    // embedding it in the payload.
                    val peerNodeId = event.dataItem.uri.host ?: continue
                    Log.d(TAG, "onDataChanged: peerNode=$peerNodeId remoteVersion=$remoteVersion")
                    scope.launch { applyIfNewer(json, peerNodeId, remoteVersion, remoteUpdatedAt) }
                }
            }
        }
    }

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path == PATH_SYNC_REQUEST_REPUBLISH) {
            Log.d(TAG, "onMessageReceived: republish request from ${event.sourceNodeId}")
            scope.launch {
                FavoritesSyncPublisher.publishNow(
                    this@FavoritesSyncListener,
                    favoritesManager(),
                    metaStore(),
                )
            }
        }
    }

    private suspend fun applyIfNewer(
        json: String,
        peerNodeId: String,
        remoteVersion: Long,
        remoteUpdatedAt: Long,
    ) {
        val meta = metaStore().read()
        val shouldApply = shouldApplyRemote(meta, peerNodeId, remoteVersion, remoteUpdatedAt)
        val lastApplied = meta.peerVersions[peerNodeId] ?: 0L
        Log.d(TAG, "applyIfNewer: peer=$peerNodeId remoteV=$remoteVersion lastApplied=$lastApplied remoteT=$remoteUpdatedAt ownT=${meta.ownUpdatedAt} shouldApply=$shouldApply")
        if (!shouldApply) return

        val payload = FavoritesSyncPayload.fromJson(json) ?: return
        favoritesManager().saveFavorites(payload.favorites)
        metaStore().writePeerVersion(peerNodeId, remoteVersion)
    }

    companion object {
        private val reconcileMutex = Mutex()
        private const val MIN_RECONCILE_INTERVAL_MS = 5_000L

        internal fun shouldApplyRemote(
            meta: SyncMetadata,
            peerNodeId: String,
            remoteVersion: Long,
            remoteUpdatedAt: Long,
        ): Boolean {
            val lastApplied = meta.peerVersions[peerNodeId] ?: 0L
            return when {
                remoteVersion > lastApplied -> true
                // Tiebreak: same logical version but peer has a newer wall-clock timestamp.
                // Covers the republish-handshake case where peer re-publishes identical state.
                remoteVersion == lastApplied && remoteUpdatedAt > meta.ownUpdatedAt -> true
                else -> false
            }
        }

        // Volatile is enough here: only written under mutex, but reads outside it need visibility.
        @Volatile private var lastReconcileAt = 0L

        suspend fun coldStartReconcile(
            context: Context,
            favoritesManager: FavoritesManager,
            metaStore: SyncMetadataStore,
        ) {
            val now = System.currentTimeMillis()
            if (now - lastReconcileAt < MIN_RECONCILE_INTERVAL_MS) {
                Log.d(TAG, "coldStartReconcile: skipping, last ran ${now - lastReconcileAt}ms ago")
                return
            }
            if (!reconcileMutex.tryLock()) {
                Log.d(TAG, "coldStartReconcile: skipping, already in progress")
                return
            }
            lastReconcileAt = now
            try {
                // Retry any pending publish before pulling remote state.
                FavoritesSyncPublisher.retryPendingIfNeeded(context, favoritesManager, metaStore)

                runCatching {
                    Log.d(TAG, "coldStartReconcile: querying $URI_FAVORITES")
                    val items = Wearable.getDataClient(context)
                        .getDataItems(URI_FAVORITES)
                        .await()
                    items.use { buffer ->
                        Log.d(TAG, "coldStartReconcile: got ${buffer.count} items")
                        for (item in buffer) {
                            val peerNodeId = item.uri.host ?: continue
                            Log.d(TAG, "coldStartReconcile: item uri=${item.uri}")
                            if (item.uri.path != PATH_FAVORITES) continue
                            val map = DataMapItem.fromDataItem(item).dataMap
                            val json = map.getString(KEY_PAYLOAD) ?: continue
                            val remoteVersion = map.getLong(KEY_VERSION)
                            val remoteUpdatedAt = map.getLong(KEY_UPDATED_AT)
                            val meta = metaStore.read()
                            val lastApplied = meta.peerVersions[peerNodeId] ?: 0L
                            val shouldApply = shouldApplyRemote(meta, peerNodeId, remoteVersion, remoteUpdatedAt)
                            Log.d(TAG, "coldStartReconcile: peer=$peerNodeId remoteV=$remoteVersion lastApplied=$lastApplied remoteT=$remoteUpdatedAt ownT=${meta.ownUpdatedAt} shouldApply=$shouldApply json=${json.take(120)}")
                            if (shouldApply) {
                                val payload = FavoritesSyncPayload.fromJson(json) ?: continue
                                Log.d(TAG, "coldStartReconcile: applying ${payload.favorites.size} favorites")
                                favoritesManager.saveFavorites(payload.favorites)
                                metaStore.writePeerVersion(peerNodeId, remoteVersion)
                            }
                        }
                    }
                }.onFailure { Log.e(TAG, "coldStartReconcile failed", it) }
            } finally {
                reconcileMutex.unlock()
            }
        }
    }
}
