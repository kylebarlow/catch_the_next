package dev.catchthenext.phone

import dev.catchthenext.android.sync.FavoritesSyncListener
import dev.catchthenext.android.sync.SyncStateStore

class PhoneFavoritesSyncListener : FavoritesSyncListener() {
    override fun syncStateStore(): SyncStateStore = PhoneGraph.syncStateStore(this)
}
