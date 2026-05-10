package dev.catchthenext.android.sync

object SyncEngine {
    private const val TOMBSTONE_GC_MS = 30L * 24 * 60 * 60 * 1000

    fun merge(local: SyncState, remoteItems: Map<String, FavoriteEntry>, now: Long): SyncState {
        val merged = local.items.toMutableMap()
        for ((id, remote) in remoteItems) {
            val existing = merged[id]
            if (existing == null || remoteWins(remote, existing)) {
                merged[id] = remote
            }
        }
        val cleaned = merged.filter { (_, e) -> !e.tombstone || (now - e.tombstoneAt) < TOMBSTONE_GC_MS }
        return local.copy(items = cleaned)
    }

    private fun remoteWins(remote: FavoriteEntry, local: FavoriteEntry): Boolean {
        if (remote.authorCounter != local.authorCounter) return remote.authorCounter > local.authorCounter
        return remote.authorNodeId > local.authorNodeId
    }
}
