package dev.catchthenext.android.sync

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

private const val TAG = "FavSync"

object CapabilityWatcher {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start(context: Context, syncStateStore: SyncStateStore) {
        val appContext = context.applicationContext

        // Register listener for future capability changes.
        Wearable.getCapabilityClient(appContext).addListener(
            { capabilityInfo ->
                // Accept any reachable node (nearby or cloud-routed).
                val nodes = capabilityInfo.nodes
                Log.d(TAG, "CapabilityWatcher: capability changed, nodes=${nodes.map { "${it.id}(nearby=${it.isNearby})" }}")
                ReachabilityState.update(nodes.isNotEmpty())
                if (nodes.isNotEmpty()) {
                    scope.launch {
                        FavoritesSyncListener.coldStartReconcile(appContext, syncStateStore)
                    }
                }
            },
            CAPABILITY_FAVORITES_SYNC,
        )

        // Query initial state — the listener only fires on changes, not current state.
        scope.launch {
            val result = runCatching {
                Wearable.getCapabilityClient(appContext)
                    .getCapability(CAPABILITY_FAVORITES_SYNC, CapabilityClient.FILTER_REACHABLE)
                    .await()
            }
            val nodes = result.getOrNull()?.nodes.orEmpty()
            Log.d(TAG, "CapabilityWatcher: initial nodes=${nodes.map { "${it.id}(nearby=${it.isNearby})" }}")
            ReachabilityState.update(nodes.isNotEmpty())
            if (nodes.isNotEmpty()) {
                FavoritesSyncListener.coldStartReconcile(appContext, syncStateStore)
            }
        }

        Log.d(TAG, "CapabilityWatcher: started, watching for $CAPABILITY_FAVORITES_SYNC")
    }
}
