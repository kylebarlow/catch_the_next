package dev.catchthenext.android.sync

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import dev.catchthenext.storage.FavoritesManager
import kotlinx.coroutines.tasks.await

private const val TAG = "FavSync"

object FavoritesSyncForcer {

    /** Full bidirectional sync: pull, push our state, and ask peer to republish. For manual button. */
    suspend fun forceSync(context: Context, favoritesManager: FavoritesManager, metaStore: SyncMetadataStore) {
        FavoritesSyncListener.coldStartReconcile(context, favoritesManager, metaStore)
        FavoritesSyncPublisher.publishNow(context, favoritesManager, metaStore)
        sendRepublishToAllReachable(context)
    }

    /** Lightweight pull only. For periodic background sync. */
    suspend fun pullOnly(context: Context, favoritesManager: FavoritesManager, metaStore: SyncMetadataStore) {
        FavoritesSyncListener.coldStartReconcile(context, favoritesManager, metaStore)
    }

    private suspend fun sendRepublishToAllReachable(context: Context) {
        runCatching {
            val info = Wearable.getCapabilityClient(context)
                .getCapability(CAPABILITY_FAVORITES_SYNC, CapabilityClient.FILTER_REACHABLE)
                .await()
            info.nodes.forEach { node ->
                runCatching {
                    Wearable.getMessageClient(context)
                        .sendMessage(node.id, PATH_SYNC_REQUEST_REPUBLISH, ByteArray(0))
                        .await()
                }.onSuccess { Log.d(TAG, "FavoritesSyncForcer: republish request sent to ${node.id}") }
                 .onFailure { Log.w(TAG, "FavoritesSyncForcer: republish request failed to ${node.id}", it) }
            }
        }.onFailure { Log.w(TAG, "FavoritesSyncForcer: capability query failed", it) }
    }
}
