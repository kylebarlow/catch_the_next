package dev.catchthenext.wear

import android.content.Context
import dev.catchthenext.android.storage.AttributionRecordingClient
import dev.catchthenext.android.storage.AttributionStore
import dev.catchthenext.api.TransitApi
import dev.catchthenext.api.TransitlandClient
import dev.catchthenext.model.Stop
import dev.catchthenext.android.storage.AndroidFavoritesManager

object WearGraph {
    @Volatile private var clientRef: TransitApi? = null
    @Volatile private var favoritesManagerRef: AndroidFavoritesManager? = null
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

    fun favoritesManager(ctx: Context): AndroidFavoritesManager = favoritesManagerRef ?: synchronized(this) {
        favoritesManagerRef ?: AndroidFavoritesManager(ctx.applicationContext).also { favoritesManagerRef = it }
    }
}
