package dev.catchthenext.android.sync

import android.content.Context
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
import kotlinx.coroutines.tasks.await

private const val KEY_PAYLOAD = "payload"
private const val KEY_UPDATED_AT = "updatedAt"
private const val KEY_VERSION = "version"

abstract class FavoritesSyncListener : WearableListenerService() {
    protected abstract fun favoritesManager(): FavoritesManager
    protected abstract fun metaStore(): SyncMetadataStore

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onDataChanged(events: DataEventBuffer) {
        events.use { buffer ->
            for (event in buffer) {
                if (event.type == DataEvent.TYPE_CHANGED &&
                    event.dataItem.uri.path == PATH_FAVORITES
                ) {
                    val map = DataMapItem.fromDataItem(event.dataItem).dataMap
                    val json = map.getString(KEY_PAYLOAD) ?: continue
                    val remoteVersion = map.getLong(KEY_VERSION)
                    val remoteUpdatedAt = map.getLong(KEY_UPDATED_AT)
                    scope.launch { applyIfNewer(json, remoteVersion, remoteUpdatedAt) }
                }
            }
        }
    }

    private suspend fun applyIfNewer(json: String, remoteVersion: Long, remoteUpdatedAt: Long) {
        val (localVersion, localUpdatedAt) = metaStore().read()
        val remoteWins = remoteVersion > localVersion ||
                (remoteVersion == localVersion && remoteUpdatedAt > localUpdatedAt)
        if (!remoteWins) return

        val payload = FavoritesSyncPayload.fromJson(json) ?: return
        favoritesManager().saveFavorites(payload.favorites)
        metaStore().write(remoteVersion, remoteUpdatedAt)
    }

    companion object {
        suspend fun coldStartReconcile(
            context: Context,
            favoritesManager: FavoritesManager,
            metaStore: SyncMetadataStore,
        ) {
            runCatching {
                val items = Wearable.getDataClient(context)
                    .getDataItems(URI_FAVORITES)
                    .await()
                items.use { buffer ->
                    for (item in buffer) {
                        if (item.uri.path != PATH_FAVORITES) continue
                        val map = DataMapItem.fromDataItem(item).dataMap
                        val json = map.getString(KEY_PAYLOAD) ?: continue
                        val remoteVersion = map.getLong(KEY_VERSION)
                        val remoteUpdatedAt = map.getLong(KEY_UPDATED_AT)
                        val (localVersion, localUpdatedAt) = metaStore.read()
                        val remoteWins = remoteVersion > localVersion ||
                                (remoteVersion == localVersion && remoteUpdatedAt > localUpdatedAt)
                        if (remoteWins) {
                            val payload = FavoritesSyncPayload.fromJson(json) ?: continue
                            favoritesManager.saveFavorites(payload.favorites)
                            metaStore.write(remoteVersion, remoteUpdatedAt)
                        }
                    }
                }
            }
        }
    }
}
