package dev.catchthenext.android.sync

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

private const val TAG = "FavSync"

abstract class FavoritesSyncListener : WearableListenerService() {
    protected abstract fun syncStateStore(): SyncStateStore

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onDataChanged(events: DataEventBuffer) {
        Log.d(TAG, "onDataChanged fired")
        events.use { buffer ->
            for (event in buffer) {
                if (event.type == DataEvent.TYPE_CHANGED && event.dataItem.uri.path == PATH_FAVORITES) {
                    val map = DataMapItem.fromDataItem(event.dataItem).dataMap
                    val json = map.getString(KEY_ITEMS) ?: continue
                    val remoteItems = SyncState.itemsFromJson(json) ?: continue
                    val peerNodeId = event.dataItem.uri.host ?: continue
                    Log.d(TAG, "onDataChanged: peer=$peerNodeId items=${remoteItems.size}")
                    scope.launch { applyMutex.withLock { applyRemote(syncStateStore(), remoteItems) } }
                }
            }
        }
    }

    companion object {
        private val applyMutex = Mutex()

        private suspend fun applyRemote(store: SyncStateStore, remoteItems: Map<String, FavoriteEntry>) {
            val now = System.currentTimeMillis()
            store.update { current ->
                val merged = SyncEngine.merge(current, remoteItems, now)
                if (merged == current) current else merged.also {
                    Log.d(TAG, "applyRemote: merged ${remoteItems.size} remote items")
                }
            }
        }

        suspend fun coldStartReconcile(context: Context, syncStateStore: SyncStateStore) {
            if (!applyMutex.tryLock()) {
                Log.d(TAG, "coldStartReconcile: already in progress, skipping")
                return
            }
            try {
                val localNodeId = runCatching {
                    Wearable.getNodeClient(context).localNode.await().id
                }.getOrNull()

                runCatching {
                    Log.d(TAG, "coldStartReconcile: querying $URI_FAVORITES")
                    val items = Wearable.getDataClient(context).getDataItems(URI_FAVORITES).await()
                    items.use { buffer ->
                        Log.d(TAG, "coldStartReconcile: got ${buffer.count} items")
                        val allRemote = mutableMapOf<String, FavoriteEntry>()
                        for (item in buffer) {
                            val peerNodeId = item.uri.host ?: continue
                            if (peerNodeId == localNodeId) continue
                            if (item.uri.path != PATH_FAVORITES) continue
                            val json = DataMapItem.fromDataItem(item).dataMap.getString(KEY_ITEMS) ?: continue
                            val remote = SyncState.itemsFromJson(json) ?: continue
                            Log.d(TAG, "coldStartReconcile: peer=$peerNodeId items=${remote.size}")
                            allRemote.putAll(remote)
                        }
                        if (allRemote.isNotEmpty()) {
                            applyRemote(syncStateStore, allRemote)
                        }
                    }
                }.onFailure { Log.e(TAG, "coldStartReconcile failed", it) }

                // Log diagnostics on first reconcile
                runCatching {
                    val connected = Wearable.getNodeClient(context).connectedNodes.await()
                    Log.d(TAG, "coldStartReconcile: connected nodes=${connected.map { "${it.id}(nearby=${it.isNearby})" }}")
                    val caps = Wearable.getCapabilityClient(context)
                        .getAllCapabilities(com.google.android.gms.wearable.CapabilityClient.FILTER_REACHABLE)
                        .await()
                    Log.d(TAG, "coldStartReconcile: reachable capabilities=${caps.keys}")
                }.onFailure { Log.w(TAG, "diagnostics failed", it) }
            } finally {
                applyMutex.unlock()
            }
        }
    }
}
