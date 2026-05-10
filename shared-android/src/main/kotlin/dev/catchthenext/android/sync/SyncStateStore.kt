package dev.catchthenext.android.sync

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.android.gms.wearable.Wearable
import dev.catchthenext.model.Stop
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await

private const val TAG = "FavSync"
private val Context.syncStateDataStore by preferencesDataStore(name = "sync_state")
private val KEY_STATE = stringPreferencesKey("state")

class SyncStateStore(private val context: Context) {
    private val nodeIdDeferred = CompletableDeferred<String>()

    suspend fun awaitNodeId(): String = nodeIdDeferred.await()

    // Resolves the local node ID and calls ensureInitialized. Called from App.onCreate.
    suspend fun initFromContext(context: Context, legacyFavorites: List<Stop>) {
        val nodeId = runCatching { Wearable.getNodeClient(context).localNode.await().id }
            .getOrDefault("unknown")
        ensureInitialized(nodeId, legacyFavorites)
    }

    // Seeds legacy favorites on first run so existing data is not lost on upgrade.
    suspend fun ensureInitialized(nodeId: String, legacyFavorites: List<Stop>) {
        update { current ->
            if (current.myNodeId.isNotEmpty()) {
                nodeIdDeferred.complete(current.myNodeId)
                return@update current
            }
            val now = System.currentTimeMillis()
            val items = legacyFavorites
                .filter { it.onestopId != null }
                .associate { stop ->
                    stop.onestopId!! to FavoriteEntry(
                        stop = stop,
                        authorNodeId = nodeId,
                        authorCounter = 1L,
                        tombstone = false,
                        tombstoneAt = 0L,
                    )
                }
            val counter = if (items.isEmpty()) 0L else 1L
            Log.d(TAG, "SyncStateStore: initialized nodeId=$nodeId legacyCount=${items.size}")
            nodeIdDeferred.complete(nodeId)
            SyncState(myNodeId = nodeId, myCounter = counter, items = items)
        }
    }

    suspend fun read(): SyncState {
        val json = context.syncStateDataStore.data.map { it[KEY_STATE] }.first()
        return json?.let { SyncState.fromJson(it) } ?: SyncState()
    }

    fun flow(): Flow<SyncState> = context.syncStateDataStore.data.map { prefs ->
        prefs[KEY_STATE]?.let { SyncState.fromJson(it) } ?: SyncState()
    }

    suspend fun update(transform: (SyncState) -> SyncState) {
        context.syncStateDataStore.edit { prefs ->
            val current = prefs[KEY_STATE]?.let { SyncState.fromJson(it) } ?: SyncState()
            val updated = transform(current)
            prefs[KEY_STATE] = updated.toJson()
        }
    }

    fun readBlocking(): SyncState = runBlocking { read() }
    fun updateBlocking(transform: (SyncState) -> SyncState) = runBlocking { update(transform) }
    fun awaitNodeIdBlocking(): String = runBlocking { nodeIdDeferred.await() }
}
