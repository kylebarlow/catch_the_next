package dev.catchthenext.wear

import dev.catchthenext.android.sync.FavoritesSyncListener
import dev.catchthenext.android.sync.SyncStateStore

class WearFavoritesSyncListener : FavoritesSyncListener() {
    override fun syncStateStore(): SyncStateStore = WearGraph.syncStateStore(this)
}
