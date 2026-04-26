package dev.catchthenext.wear

import dev.catchthenext.android.sync.FavoritesSyncListener
import dev.catchthenext.android.sync.SyncMetadataStore
import dev.catchthenext.storage.FavoritesManager

class WearFavoritesSyncListener : FavoritesSyncListener() {
    override fun favoritesManager(): FavoritesManager = WearGraph.favoritesManager(this)
    override fun metaStore(): SyncMetadataStore = SyncMetadataStore(this)
}
