package dev.catchthenext.android.sync

import android.content.Context
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import dev.catchthenext.storage.FavoritesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val KEY_PAYLOAD = "payload"
private const val KEY_UPDATED_AT = "updatedAt"
private const val KEY_VERSION = "version"
private const val DEBOUNCE_MS = 250L

object FavoritesSyncPublisher {
    @Volatile private var debounce: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun attach(context: Context, favoritesManager: FavoritesManager, metaStore: SyncMetadataStore) {
        scope.launch {
            favoritesManager.favoritesFlow().collect {
                debounce?.cancel()
                debounce = launch {
                    delay(DEBOUNCE_MS)
                    publish(context, favoritesManager, metaStore)
                }
            }
        }
    }

    private suspend fun publish(
        context: Context,
        favoritesManager: FavoritesManager,
        metaStore: SyncMetadataStore,
    ) {
        val (localVersion, _) = metaStore.read()
        val newVersion = localVersion + 1
        val updatedAt = System.currentTimeMillis()
        metaStore.write(newVersion, updatedAt)

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

        Wearable.getDataClient(context)
            .putDataItem(request)
            .addOnFailureListener { /* sync is best-effort */ }
    }
}
