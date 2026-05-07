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
private const val DEBOUNCE_MS = 500L

object FavoritesSyncPublisher {
    @Volatile private var debounce: Job? = null
    @Volatile private var attached = false

    // Content hash of the last list we successfully published. Used to suppress redundant
    // re-publishes when the listener applies a remote payload (which writes to local DataStore,
    // causing the flow to re-emit the same content we just received).
    @Volatile private var lastPublishedHash: Int = 0

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun attach(context: Context, favoritesManager: FavoritesManager, metaStore: SyncMetadataStore) {
        if (attached) return
        attached = true
        scope.launch {
            favoritesManager.favoritesFlow().distinctUntilChanged().collect { favorites ->
                // Echo guard: skip if this is the same list we already published — the emission
                // was caused by the listener writing back a remote payload we just received.
                val hash = favorites.hashCode()
                if (hash == lastPublishedHash) return@collect

                // Empty-publish guard: don't publish an empty list on a fresh install.
                // Once the device has ever published (localUpdatedAt > 0), publishing empty means
                // the user deliberately cleared their favorites and the peer should learn that.
                val meta = metaStore.read()
                if (favorites.isEmpty() && meta.localUpdatedAt == 0L) return@collect

                debounce?.cancel()
                debounce = launch {
                    delay(DEBOUNCE_MS)
                    publish(context, favoritesManager, metaStore)
                }
            }
        }
    }

    /** Mark a list as published without going through the flow path. Called by FavoritesSyncPusher. */
    fun markPublished(hash: Int) {
        lastPublishedHash = hash
    }

    private suspend fun publish(
        context: Context,
        favoritesManager: FavoritesManager,
        metaStore: SyncMetadataStore,
    ) {
        val updatedAt = System.currentTimeMillis()
        val favorites = favoritesManager.getFavorites()
        val payload = FavoritesSyncPayload(favorites = favorites, updatedAt = updatedAt)

        val request = PutDataMapRequest.create(PATH_FAVORITES).apply {
            dataMap.putString(KEY_PAYLOAD, payload.toJson())
            dataMap.putLong(KEY_UPDATED_AT, updatedAt)
        }.asPutDataRequest().setUrgent()

        val result = runCatching {
            Wearable.getDataClient(context).putDataItem(request).await()
        }
        Log.d(TAG, "publish: favorites=${favorites.size} succeeded=${result.isSuccess} err=${result.exceptionOrNull()?.message}")
        if (result.isSuccess) {
            metaStore.writeLocalUpdatedAt(updatedAt)
            lastPublishedHash = favorites.hashCode()
        }
    }
}
