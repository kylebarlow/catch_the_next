package dev.catchthenext.android.di

import android.content.Context
import dev.catchthenext.android.storage.AttributionRecordingClient
import dev.catchthenext.android.storage.AttributionStore
import dev.catchthenext.android.storage.SyncedFavoritesManager
import dev.catchthenext.android.sync.SyncStateStore
import dev.catchthenext.api.TransitApi
import dev.catchthenext.api.TransitlandClient
import dev.catchthenext.model.Stop

/**
 * The DI singletons shared by the phone and wear apps. Per-app graphs subclass this and supply
 * the BuildConfig-derived construction parameters (BuildConfig is per-module, so it can't live
 * here). A null [userAgent] leaves [TransitlandClient]'s default User-Agent in place.
 */
open class CommonGraph(
    private val apiKey: String,
    private val baseUrl: String,
    private val userAgent: String? = null,
) {
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
            val ctx = requireNotNull(appCtx) { "init(ctx) must be called before transitlandClient()" }
            val delegate = if (userAgent != null) {
                TransitlandClient(apiKey = apiKey, baseUrl = baseUrl, userAgent = userAgent)
            } else {
                TransitlandClient(apiKey = apiKey, baseUrl = baseUrl)
            }
            AttributionRecordingClient(delegate = delegate, store = AttributionStore(ctx))
        }.also { clientRef = it }
    }

    fun syncStateStore(ctx: Context): SyncStateStore = syncStateStoreRef ?: synchronized(this) {
        syncStateStoreRef ?: SyncStateStore(ctx.applicationContext).also { syncStateStoreRef = it }
    }

    fun favoritesManager(ctx: Context): SyncedFavoritesManager = favoritesManagerRef ?: synchronized(this) {
        favoritesManagerRef ?: SyncedFavoritesManager(syncStateStore(ctx)).also { favoritesManagerRef = it }
    }
}
