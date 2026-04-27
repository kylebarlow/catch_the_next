package dev.catchthenext.android.sync

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.Wearable
import dev.catchthenext.storage.FavoritesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

private const val TAG = "FavSync"

/**
 * Watches for the peer device advertising `favorites_sync_v1` capability via Bluetooth.
 * When the peer transitions from unreachable → reachable, triggers a cold-start reconcile
 * and sends a republish request so the peer pushes its current state, bypassing any stale
 * local Data Layer cache.
 *
 * Call [start] once from Application.onCreate. The listener is process-scoped; no unregister
 * is needed (the listener is GC'd with the process).
 */
object CapabilityWatcher {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start(context: Context, favoritesManager: FavoritesManager, metaStore: SyncMetadataStore) {
        val appContext = context.applicationContext
        // addListener(capability: String) is capability-name-based; filterType is not a parameter.
        // We filter reachable nodes ourselves via node.isNearby in the callback.
        Wearable.getCapabilityClient(appContext).addListener(
            { capabilityInfo ->
                val reachable = capabilityInfo.nodes.filter { it.isNearby }
                Log.d(TAG, "CapabilityWatcher: capability changed, reachable=${reachable.map { it.id }}")
                if (reachable.isNotEmpty()) {
                    scope.launch {
                        FavoritesSyncListener.coldStartReconcile(appContext, favoritesManager, metaStore)
                        FavoritesSyncPublisher.retryPendingIfNeeded(appContext, favoritesManager, metaStore)
                        reachable.forEach { node ->
                            sendRepublishRequest(appContext, node.id)
                        }
                    }
                }
            },
            CAPABILITY_FAVORITES_SYNC,
        )
        Log.d(TAG, "CapabilityWatcher: started, watching for $CAPABILITY_FAVORITES_SYNC")
    }

    private suspend fun sendRepublishRequest(context: Context, nodeId: String) {
        runCatching {
            Wearable.getMessageClient(context)
                .sendMessage(nodeId, PATH_SYNC_REQUEST_REPUBLISH, ByteArray(0))
                .await()
        }.onSuccess {
            Log.d(TAG, "sendRepublishRequest: sent to $nodeId")
        }.onFailure {
            Log.w(TAG, "sendRepublishRequest: failed to $nodeId", it)
        }
    }
}
