package dev.catchthenext.phone

import android.content.Context
import dev.catchthenext.api.TransitlandClient
import dev.catchthenext.android.storage.AndroidFavoritesManager
import dev.catchthenext.model.Stop

object PhoneGraph {
    @Volatile private var clientRef: TransitlandClient? = null
    @Volatile var pendingConfirmStop: Stop? = null

    fun transitlandClient(): TransitlandClient = clientRef ?: synchronized(this) {
        clientRef ?: TransitlandClient(
            apiKey = BuildConfig.APP_API_KEY,
            baseUrl = BuildConfig.CATCH_THE_NEXT_BASE_URL,
            userAgent = "CatchTheNext/1.0 (Android Phone; +https://codeberg.org/ursidaureus/catch_the_next)",
        ).also { clientRef = it }
    }

    fun favoritesManager(ctx: Context): AndroidFavoritesManager =
        AndroidFavoritesManager(ctx.applicationContext)
}
