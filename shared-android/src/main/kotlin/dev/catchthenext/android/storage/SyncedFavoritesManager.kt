package dev.catchthenext.android.storage

import dev.catchthenext.android.sync.FavoriteEntry
import dev.catchthenext.android.sync.SyncStateStore
import dev.catchthenext.model.Stop
import dev.catchthenext.storage.FavoritesManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class SyncedFavoritesManager(private val store: SyncStateStore) : FavoritesManager {

    override fun favoritesFlow(): Flow<List<Stop>> = store.flow().map { state ->
        state.items.values.filter { !it.tombstone }.mapNotNull { it.stop }
    }

    override fun getFavorites(): List<Stop> =
        store.readBlocking().items.values.filter { !it.tombstone }.mapNotNull { it.stop }

    override fun addFavorite(stop: Stop) {
        val id = stop.onestopId ?: return
        store.updateBlocking { state ->
            if (state.items[id]?.tombstone == false) return@updateBlocking state
            val nodeId = store.awaitNodeIdBlocking()
            val counter = state.myCounter + 1
            state.copy(
                myCounter = counter,
                items = state.items + (id to FavoriteEntry(
                    stop = stop,
                    authorNodeId = nodeId,
                    authorCounter = counter,
                    tombstone = false,
                    tombstoneAt = 0L,
                ))
            )
        }
    }

    override fun removeFavorite(onestopId: String): Boolean {
        var removed = false
        store.updateBlocking { state ->
            val entry = state.items[onestopId]
            if (entry == null || entry.tombstone) return@updateBlocking state
            removed = true
            val nodeId = store.awaitNodeIdBlocking()
            val counter = state.myCounter + 1
            state.copy(
                myCounter = counter,
                items = state.items + (onestopId to entry.copy(
                    authorNodeId = nodeId,
                    authorCounter = counter,
                    tombstone = true,
                    tombstoneAt = System.currentTimeMillis(),
                ))
            )
        }
        return removed
    }

    override fun isFavorite(onestopId: String): Boolean =
        store.readBlocking().items[onestopId]?.tombstone == false

    // Used by resolveStaleStops to replace the full list with fresh stop IDs.
    override fun saveFavorites(stops: List<Stop>) {
        store.updateBlocking { state ->
            val nodeId = store.awaitNodeIdBlocking()
            val counter = state.myCounter + 1
            val now = System.currentTimeMillis()
            val newIds = stops.mapNotNull { it.onestopId }.toSet()
            val newItems = state.items.toMutableMap()
            for ((id, entry) in state.items) {
                if (!entry.tombstone && id !in newIds) {
                    newItems[id] = entry.copy(
                        authorNodeId = nodeId,
                        authorCounter = counter,
                        tombstone = true,
                        tombstoneAt = now,
                    )
                }
            }
            for (stop in stops) {
                val id = stop.onestopId ?: continue
                newItems[id] = FavoriteEntry(
                    stop = stop,
                    authorNodeId = nodeId,
                    authorCounter = counter,
                    tombstone = false,
                    tombstoneAt = 0L,
                )
            }
            state.copy(myCounter = counter, items = newItems)
        }
    }
}
