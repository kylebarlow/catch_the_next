package dev.catchthenext.android.sync

import android.content.Context
import android.net.Uri
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

// Conflict resolution: pure wall-clock LWW. If both phone and watch edit favorites while
// disconnected, whichever device has the more recent wall-clock timestamp wins. Simultaneous
// offline edits are rare for a transit favorites app; the simplicity tradeoff is intentional.
abstract class FavoritesSyncListener : WearableListenerService() {
    protected abstract fun favoritesManager(): FavoritesManager
    protected abstract fun metaStore(): SyncMetadataStore

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onDataChanged(events: DataEventBuffer) {
        Log.d(TAG, "onDataChanged fired")
        events.use { buffer ->
            for (event in buffer) {
                if (event.type == DataEvent.TYPE_CHANGED &&
                    event.dataItem.uri.path == PATH_FAVORITES
                ) {
                    val map = DataMapItem.fromDataItem(event.dataItem).dataMap
                    val json = map.getString(KEY_PAYLOAD) ?: continue
                    val remoteUpdatedAt = map.getLong(KEY_UPDATED_AT)
                    val peerNodeId = event.dataItem.uri.host ?: continue
                    Log.d(TAG, "onDataChanged: peer=$peerNodeId remoteTs=$remoteUpdatedAt")
                    scope.launch { applyIfNewer(json, remoteUpdatedAt) }
                }
            }
        }
    }

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path == PATH_REPLACE_WITH_MINE) {
            Log.d(TAG, "onMessageReceived: replace-with-mine from ${event.sourceNodeId}")
            scope.launch { applyForcedReplace(event.data) }
        }
    }

    private suspend fun applyIfNewer(json: String, remoteUpdatedAt: Long) {
        val meta = metaStore().read()
        val shouldApply = remoteUpdatedAt > meta.localUpdatedAt
        Log.d(TAG, "applyIfNewer: remoteTs=$remoteUpdatedAt localTs=${meta.localUpdatedAt} apply=$shouldApply")
        if (!shouldApply) return
        val payload = FavoritesSyncPayload.fromJson(json) ?: return
        Log.d(TAG, "applyIfNewer: applying ${payload.favorites.size} favorites")
        favoritesManager().saveFavorites(payload.favorites)
        metaStore().writeLocalUpdatedAt(remoteUpdatedAt)
    }

    private suspend fun applyForcedReplace(rawData: ByteArray) {
        val payload = FavoritesSyncPayload.fromBytes(rawData) ?: run {
            Log.w(TAG, "applyForcedReplace: failed to parse payload")
            return
        }
        Log.d(TAG, "applyForcedReplace: replacing with ${payload.favorites.size} favorites")
        favoritesManager().saveFavorites(payload.favorites)
        metaStore().writeLocalUpdatedAt(payload.updatedAt)
    }

    companion object {
        private val reconcileMutex = Mutex()

        suspend fun coldStartReconcile(
            context: Context,
            favoritesManager: FavoritesManager,
            metaStore: SyncMetadataStore,
        ) {
            if (!reconcileMutex.tryLock()) {
                Log.d(TAG, "coldStartReconcile: already in progress, skipping")
                return
            }
            try {
                val localNodeId = runCatching {
                    Wearable.getNodeClient(context).localNode.await().id
                }.getOrNull()

                runCatching {
                    Log.d(TAG, "coldStartReconcile: querying $URI_FAVORITES")
                    val items = Wearable.getDataClient(context)
                        .getDataItems(URI_FAVORITES)
                        .await()
                    items.use { buffer ->
                        Log.d(TAG, "coldStartReconcile: got ${buffer.count} items")
                        val meta = metaStore.read()
                        for (item in buffer) {
                            val peerNodeId = item.uri.host ?: continue
                            if (peerNodeId == localNodeId) continue  // skip own published item
                            if (item.uri.path != PATH_FAVORITES) continue
                            val map = DataMapItem.fromDataItem(item).dataMap
                            val json = map.getString(KEY_PAYLOAD) ?: continue
                            val remoteUpdatedAt = map.getLong(KEY_UPDATED_AT)
                            val shouldApply = remoteUpdatedAt > meta.localUpdatedAt
                            Log.d(TAG, "coldStartReconcile: peer=$peerNodeId remoteTs=$remoteUpdatedAt localTs=${meta.localUpdatedAt} apply=$shouldApply")
                            if (shouldApply) {
                                val payload = FavoritesSyncPayload.fromJson(json) ?: continue
                                Log.d(TAG, "coldStartReconcile: applying ${payload.favorites.size} favorites")
                                favoritesManager.saveFavorites(payload.favorites)
                                metaStore.writeLocalUpdatedAt(remoteUpdatedAt)
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
