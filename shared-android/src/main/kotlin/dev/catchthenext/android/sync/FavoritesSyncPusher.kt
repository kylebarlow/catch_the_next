package dev.catchthenext.android.sync

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import dev.catchthenext.storage.FavoritesManager
import kotlinx.coroutines.tasks.await

private const val TAG = "FavSync"
private const val KEY_PAYLOAD = "payload"
private const val KEY_UPDATED_AT = "updatedAt"

sealed class PushResult {
    object Sent : PushResult()
    object PeerUnreachable : PushResult()
    data class Failed(val reason: String) : PushResult()
}

object FavoritesSyncPusher {

    /**
     * Pushes this device's current favorites to the peer, replacing the peer's list.
     * The payload is embedded in a WearOS message so delivery does not depend on Data Layer
     * replication timing. Also updates the local Data Layer cache for future auto-sync.
     */
    suspend fun pushToPeer(
        context: Context,
        favoritesManager: FavoritesManager,
        metaStore: SyncMetadataStore,
    ): PushResult {
        val nodes = runCatching {
            Wearable.getCapabilityClient(context)
                .getCapability(CAPABILITY_FAVORITES_SYNC, CapabilityClient.FILTER_REACHABLE)
                .await()
                .nodes
        }.getOrElse { e ->
            Log.w(TAG, "pushToPeer: capability query failed", e)
            return PushResult.Failed(e.message ?: "capability query failed")
        }

        if (nodes.isEmpty()) {
            Log.d(TAG, "pushToPeer: no reachable peers")
            return PushResult.PeerUnreachable
        }

        val updatedAt = System.currentTimeMillis()
        val favorites = favoritesManager.getFavorites()
        val payload = FavoritesSyncPayload(favorites = favorites, updatedAt = updatedAt)
        val payloadBytes = payload.toJson().toByteArray(Charsets.UTF_8)

        // Update local Data Layer cache so future auto-sync on the peer reflects our push.
        runCatching {
            val request = PutDataMapRequest.create(PATH_FAVORITES).apply {
                dataMap.putString(KEY_PAYLOAD, payload.toJson())
                dataMap.putLong(KEY_UPDATED_AT, updatedAt)
            }.asPutDataRequest().setUrgent()
            Wearable.getDataClient(context).putDataItem(request).await()
        }.onSuccess {
            metaStore.writeLocalUpdatedAt(updatedAt)
            FavoritesSyncPublisher.markPublished(favorites.hashCode())
        }.onFailure {
            Log.w(TAG, "pushToPeer: putDataItem failed (continuing with message send)", it)
        }

        // Send the payload directly via message so the peer applies it immediately,
        // independent of Data Layer replication timing.
        return runCatching {
            for (node in nodes) {
                Log.d(TAG, "pushToPeer: sending to ${node.id} (${node.displayName})")
                Wearable.getMessageClient(context)
                    .sendMessage(node.id, PATH_REPLACE_WITH_MINE, payloadBytes)
                    .await()
            }
            Log.d(TAG, "pushToPeer: sent ${favorites.size} favorites to ${nodes.size} peer(s)")
            PushResult.Sent
        }.getOrElse { e ->
            Log.w(TAG, "pushToPeer: message send failed", e)
            PushResult.Failed(e.message ?: "message send failed")
        }
    }
}
