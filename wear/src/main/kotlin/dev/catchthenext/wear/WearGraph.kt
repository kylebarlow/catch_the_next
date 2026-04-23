package dev.catchthenext.wear

import android.content.Context
import dev.catchthenext.api.TransitlandClient
import dev.catchthenext.wear.storage.AndroidFavoritesManager

object WearGraph {
    @Volatile private var clientRef: TransitlandClient? = null

    fun transitlandClient(): TransitlandClient = clientRef ?: synchronized(this) {
        clientRef ?: TransitlandClient(BuildConfig.APP_API_KEY, BuildConfig.CATCH_THE_NEXT_BASE_URL)
            .also { clientRef = it }
    }

    fun favoritesManager(ctx: Context): AndroidFavoritesManager =
        AndroidFavoritesManager(ctx.applicationContext)
}
