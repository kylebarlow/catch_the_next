package dev.catchthenext.android.sync

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.Wearable
import dev.catchthenext.storage.FavoritesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private const val TAG = "FavSync"

/**
 * Watches for the peer device advertising `favorites_sync_v1` capability via Bluetooth.
 * When the peer transitions from unreachable → reachable, triggers a cold-start reconcile
 * to pull any pending changes that arrived while disconnected.
 *
 * Call [start] once from Application.onCreate. The listener is process-scoped.
 */
object CapabilityWatcher {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start(context: Context, favoritesManager: FavoritesManager, metaStore: SyncMetadataStore) {
        val appContext = context.applicationContext
        Wearable.getCapabilityClient(appContext).addListener(
            { capabilityInfo ->
                val reachable = capabilityInfo.nodes.filter { it.isNearby }
                Log.d(TAG, "CapabilityWatcher: capability changed, reachable=${reachable.map { it.id }}")
                if (reachable.isNotEmpty()) {
                    scope.launch {
                        FavoritesSyncListener.coldStartReconcile(appContext, favoritesManager, metaStore)
                    }
                }
            },
            CAPABILITY_FAVORITES_SYNC,
        )
        Log.d(TAG, "CapabilityWatcher: started, watching for $CAPABILITY_FAVORITES_SYNC")
    }
}
