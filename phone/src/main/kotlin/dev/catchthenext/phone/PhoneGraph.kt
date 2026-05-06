package dev.catchthenext.phone

import android.content.Context
import dev.catchthenext.android.storage.AttributionRecordingClient
import dev.catchthenext.android.storage.AttributionStore
import dev.catchthenext.api.TransitApi
import dev.catchthenext.api.TransitlandClient
import dev.catchthenext.android.storage.AndroidFavoritesManager
import dev.catchthenext.model.Stop

object PhoneGraph {
    @Volatile private var clientRef: TransitApi? = null
    @Volatile private var favoritesManagerRef: AndroidFavoritesManager? = null
    @Volatile private var appCtx: Context? = null
    @Volatile var pendingConfirmStop: Stop? = null

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

    fun favoritesManager(ctx: Context): AndroidFavoritesManager = favoritesManagerRef ?: synchronized(this) {
        favoritesManagerRef ?: AndroidFavoritesManager(ctx.applicationContext).also { favoritesManagerRef = it }
    }
}
