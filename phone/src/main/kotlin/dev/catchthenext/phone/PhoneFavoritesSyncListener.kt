package dev.catchthenext.phone

import dev.catchthenext.android.sync.FavoritesSyncListener
import dev.catchthenext.android.sync.SyncMetadataStore
import dev.catchthenext.storage.FavoritesManager

class PhoneFavoritesSyncListener : FavoritesSyncListener() {
    override fun favoritesManager(): FavoritesManager = PhoneGraph.favoritesManager(this)
    override fun metaStore(): SyncMetadataStore = SyncMetadataStore(this)
}
