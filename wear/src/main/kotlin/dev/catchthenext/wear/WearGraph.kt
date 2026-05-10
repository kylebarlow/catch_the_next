package dev.catchthenext.wear

import android.content.Context
import dev.catchthenext.android.storage.AttributionRecordingClient
import dev.catchthenext.android.storage.AttributionStore
import dev.catchthenext.android.storage.SyncedFavoritesManager
import dev.catchthenext.android.sync.SyncStateStore
import dev.catchthenext.api.TransitApi
import dev.catchthenext.api.TransitlandClient
import dev.catchthenext.model.Stop

object WearGraph {
    @Volatile private var clientRef: TransitApi? = null
    @Volatile private var syncStateStoreRef: SyncStateStore? = null
    @Volatile private var favoritesManagerRef: SyncedFavoritesManager? = null
    @Volatile private var appCtx: Context? = null
    @Volatile var pendingConfirmStop: Stop? = null

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
    }

    fun transitlandClient(): TransitApi = clientRef ?: synchronized(this) {
        clientRef ?: run {
            val ctx = requireNotNull(appCtx) { "WearGraph.init(ctx) must be called before transitlandClient()" }
            AttributionRecordingClient(
                delegate = TransitlandClient(BuildConfig.APP_API_KEY, BuildConfig.CATCH_THE_NEXT_BASE_URL),
                store = AttributionStore(ctx),
            )
        }.also { clientRef = it }
    }

    fun syncStateStore(ctx: Context): SyncStateStore = syncStateStoreRef ?: synchronized(this) {
        syncStateStoreRef ?: SyncStateStore(ctx.applicationContext).also { syncStateStoreRef = it }
    }

    fun favoritesManager(ctx: Context): SyncedFavoritesManager = favoritesManagerRef ?: synchronized(this) {
        favoritesManagerRef ?: SyncedFavoritesManager(syncStateStore(ctx)).also { favoritesManagerRef = it }
    }
}
