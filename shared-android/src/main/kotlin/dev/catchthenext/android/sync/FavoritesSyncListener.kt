package dev.catchthenext.android.sync

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
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
                    Log.d(TAG, "onDataChanged: remoteVersion=$remoteVersion")
                    scope.launch { applyIfNewer(json, remoteVersion, remoteUpdatedAt) }
                }
            }
        }
    }

    private suspend fun applyIfNewer(json: String, remoteVersion: Long, remoteUpdatedAt: Long) {
        val (_, localUpdatedAt) = metaStore().read()
        val remoteWins = remoteUpdatedAt > localUpdatedAt
        Log.d(TAG, "applyIfNewer: remoteT=$remoteUpdatedAt localT=$localUpdatedAt remoteWins=$remoteWins")
        if (!remoteWins) return

        val payload = FavoritesSyncPayload.fromJson(json) ?: return
        favoritesManager().saveFavorites(payload.favorites)
        metaStore().write(remoteVersion, remoteUpdatedAt)
    }

    companion object {
        private val reconcileMutex = Mutex()
        private const val MIN_RECONCILE_INTERVAL_MS = 30_000L

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
            runCatching {
                Log.d(TAG, "coldStartReconcile: querying $URI_FAVORITES")
                val items = Wearable.getDataClient(context)
                    .getDataItems(URI_FAVORITES)
                    .await()
                items.use { buffer ->
                    Log.d(TAG, "coldStartReconcile: got ${buffer.count} items")
                    for (item in buffer) {
                        Log.d(TAG, "coldStartReconcile: item uri=${item.uri}")
                        if (item.uri.path != PATH_FAVORITES) continue
                        val map = DataMapItem.fromDataItem(item).dataMap
                        val json = map.getString(KEY_PAYLOAD) ?: continue
                        val remoteVersion = map.getLong(KEY_VERSION)
                        val remoteUpdatedAt = map.getLong(KEY_UPDATED_AT)
                        val (_, localUpdatedAt) = metaStore.read()
                        val remoteWins = remoteUpdatedAt > localUpdatedAt
                        Log.d(TAG, "coldStartReconcile: remoteT=$remoteUpdatedAt localT=$localUpdatedAt remoteWins=$remoteWins json=${json.take(120)}")
                        if (remoteWins) {
                            val payload = FavoritesSyncPayload.fromJson(json) ?: continue
                            Log.d(TAG, "coldStartReconcile: applying ${payload.favorites.size} favorites")
                            favoritesManager.saveFavorites(payload.favorites)
                            metaStore.write(remoteVersion, remoteUpdatedAt)
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
