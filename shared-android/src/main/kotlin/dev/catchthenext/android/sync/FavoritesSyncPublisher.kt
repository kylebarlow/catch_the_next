package dev.catchthenext.android.sync

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import dev.catchthenext.storage.FavoritesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

private const val TAG = "FavSync"

private const val KEY_PAYLOAD = "payload"
private const val KEY_UPDATED_AT = "updatedAt"
private const val KEY_VERSION = "version"
private const val DEBOUNCE_MS = 250L

object FavoritesSyncPublisher {
    @Volatile private var debounce: Job? = null
    @Volatile private var attached = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun attach(context: Context, favoritesManager: FavoritesManager, metaStore: SyncMetadataStore) {
        if (attached) return
        attached = true
        scope.launch {
            var seenFirst = false
            favoritesManager.favoritesFlow().distinctUntilChanged().collect { favorites ->
                if (!seenFirst) {
                    seenFirst = true
                    // Skip the initial emission if favorites are empty — the app just started and
                    // has nothing to contribute. Subsequent changes (sync or user action) are
                    // still published. This prevents overwriting the other device's data on startup.
                    if (favorites.isEmpty()) return@collect
                }
                debounce?.cancel()
                debounce = launch {
                    delay(DEBOUNCE_MS)
                    publish(context, favoritesManager, metaStore)
                }
            }
        }
    }

    /** Publish immediately, bypassing the debounce. Used by the republish handshake. */
    suspend fun publishNow(context: Context, favoritesManager: FavoritesManager, metaStore: SyncMetadataStore) {
        publish(context, favoritesManager, metaStore)
    }

    /**
     * If a previous putDataItem failed, retry it now. Called from coldStartReconcile and
     * CapabilityWatcher when the peer becomes reachable.
     */
    suspend fun retryPendingIfNeeded(context: Context, favoritesManager: FavoritesManager, metaStore: SyncMetadataStore) {
        if (metaStore.read().publishPending) {
            Log.d(TAG, "retryPendingIfNeeded: pending publish found, retrying")
            publish(context, favoritesManager, metaStore)
        }
    }

    private suspend fun publish(
        context: Context,
        favoritesManager: FavoritesManager,
        metaStore: SyncMetadataStore,
    ) {
        val meta = metaStore.read()
        val newVersion = meta.ownVersion + 1
        val updatedAt = System.currentTimeMillis()

        val payload = FavoritesSyncPayload(
            favorites = favoritesManager.getFavorites(),
            updatedAt = updatedAt,
            version = newVersion,
        )

        val request = PutDataMapRequest.create(PATH_FAVORITES).apply {
            dataMap.putString(KEY_PAYLOAD, payload.toJson())
            dataMap.putLong(KEY_UPDATED_AT, updatedAt)
            dataMap.putLong(KEY_VERSION, newVersion)
        }.asPutDataRequest().setUrgent()

        // Only bump local version after the Data Layer accepts the item.
        // putDataItem queues delivery even when the peer is disconnected (that case succeeds
        // locally and syncs on reconnect). It only fails if Play Services itself is unavailable.
        // Keeping the version at ownVersion on failure means future remote updates are not
        // wrongly rejected as "older."
        val result = runCatching {
            Wearable.getDataClient(context).putDataItem(request).await()
        }
        val succeeded = result.isSuccess
        Log.d(TAG, "publish: version=$newVersion favorites=${payload.favorites.size} succeeded=$succeeded err=${result.exceptionOrNull()?.message}")
        if (succeeded) {
            metaStore.writeOwn(newVersion, updatedAt)
        } else {
            metaStore.setPublishPending(true)
        }
    }
}
