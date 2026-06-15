package dev.catchthenext.android.sync

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

private const val TAG = "FavSync"
private const val DEBOUNCE_MS = 500L

object FavoritesSyncPublisher {
    @Volatile private var debounce: Job? = null
    @Volatile private var attached = false
    @Volatile private var lastPublishedCounter: Long = -1L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun attach(context: Context, store: SyncStateStore) {
        if (attached) return
        attached = true
        scope.launch {
            store.flow()
                .map { it.myCounter }
                .distinctUntilChanged()
                .collect { counter ->
                    if (counter == lastPublishedCounter) return@collect
                    debounce?.cancel()
                    debounce = launch {
                        delay(DEBOUNCE_MS)
                        publish(context, store)
                    }
                }
        }
    }

    // Called by FavoritesSyncWorker to re-push current state even if counter is unchanged.
    suspend fun republish(context: Context, store: SyncStateStore) {
        publish(context, store)
    }

    private suspend fun publish(context: Context, store: SyncStateStore) {
        val state = store.read()
        if (state.myCounter == 0L && state.items.isEmpty()) return
        val json = SyncState.itemsToJson(state.items)
        val request = PutDataMapRequest.create(PATH_FAVORITES).apply {
            dataMap.putString(KEY_ITEMS, json)
        }.asPutDataRequest().setUrgent()
        val result = runCatching { Wearable.getDataClient(context).putDataItem(request).await() }
        Log.d(TAG, "publish: counter=${state.myCounter} items=${state.items.size} ok=${result.isSuccess} err=${result.exceptionOrNull()?.message}")
        if (result.isSuccess) {
            lastPublishedCounter = state.myCounter
        }
    }
}
