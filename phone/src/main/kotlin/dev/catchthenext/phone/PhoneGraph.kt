package dev.catchthenext.phone

import android.content.Context
import dev.catchthenext.android.storage.AttributionRecordingClient
import dev.catchthenext.android.storage.AttributionStore
import dev.catchthenext.android.storage.SyncedFavoritesManager
import dev.catchthenext.android.sync.SyncStateStore
import dev.catchthenext.api.TransitApi
import dev.catchthenext.api.TransitlandClient
import dev.catchthenext.model.Stop
import dev.catchthenext.phone.liveupdate.LiveUpdateController
import dev.catchthenext.phone.liveupdate.LiveUpdateStateStore

object PhoneGraph {
    @Volatile private var clientRef: TransitApi? = null
    @Volatile private var syncStateStoreRef: SyncStateStore? = null
    @Volatile private var favoritesManagerRef: SyncedFavoritesManager? = null
    @Volatile private var liveUpdateStateStoreRef: LiveUpdateStateStore? = null
    @Volatile private var liveUpdateControllerRef: LiveUpdateController? = null
    @Volatile private var appCtx: Context? = null
    @Volatile var pendingConfirmStop: Stop? = null
    @Volatile var pendingDeepLinkStopId: String? = null

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
    }

    fun transitlandClient(): TransitApi = clientRef ?: synchronized(this) {
        clientRef ?: run {
            val ctx = requireNotNull(appCtx) { "PhoneGraph.init(ctx) must be called before transitlandClient()" }
            AttributionRecordingClient(
                delegate = TransitlandClient(
                    apiKey = BuildConfig.APP_API_KEY,
                    baseUrl = BuildConfig.CATCH_THE_NEXT_BASE_URL,
                    userAgent = "CatchTheNext/1.0 (Android Phone; +https://codeberg.org/ursidaureus/catch_the_next)",
                ),
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

    fun liveUpdateStateStore(ctx: Context): LiveUpdateStateStore = liveUpdateStateStoreRef ?: synchronized(this) {
        liveUpdateStateStoreRef ?: LiveUpdateStateStore(ctx.applicationContext).also { liveUpdateStateStoreRef = it }
    }

    fun liveUpdateController(ctx: Context): LiveUpdateController = liveUpdateControllerRef ?: synchronized(this) {
        liveUpdateControllerRef ?: LiveUpdateController(
            ctx.applicationContext,
            liveUpdateStateStore(ctx),
        ).also { liveUpdateControllerRef = it }
    }
}
