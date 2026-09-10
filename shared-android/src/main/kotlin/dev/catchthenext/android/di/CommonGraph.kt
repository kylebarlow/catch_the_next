package dev.catchthenext.android.di

import android.content.Context
import dev.catchthenext.android.storage.AttributionRecordingClient
import dev.catchthenext.android.storage.AttributionStore
import dev.catchthenext.android.storage.SyncedFavoritesManager
import dev.catchthenext.android.sync.FavoritesSyncController
import dev.catchthenext.android.sync.SyncStateStore
import dev.catchthenext.android.sync.defaultFavoritesSyncController
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
    // OkHttp budgets; each app picks its own (the watch is stricter than the phone).
    private val connectTimeoutMs: Long = 15_000L,
    private val readTimeoutMs: Long = 30_000L,
    private val callTimeoutMs: Long = 0L,
) {
    @Volatile private var clientRef: TransitApi? = null
    @Volatile private var syncStateStoreRef: SyncStateStore? = null
    @Volatile private var favoritesManagerRef: SyncedFavoritesManager? = null
    @Volatile private var favoritesSyncControllerRef: FavoritesSyncController? = null
    @Volatile private var appCtx: Context? = null
    @Volatile var pendingConfirmStop: Stop? = null

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
    }

    fun transitlandClient(): TransitApi = clientRef ?: synchronized(this) {
        clientRef ?: run {
            val ctx = requireNotNull(appCtx) { "init(ctx) must be called before transitlandClient()" }
            val delegate = if (userAgent != null) {
                TransitlandClient(
                    apiKey = apiKey,
                    baseUrl = baseUrl,
                    userAgent = userAgent,
                    connectTimeoutMs = connectTimeoutMs,
                    readTimeoutMs = readTimeoutMs,
                    callTimeoutMs = callTimeoutMs,
                )
            } else {
                TransitlandClient(
                    apiKey = apiKey,
                    baseUrl = baseUrl,
                    connectTimeoutMs = connectTimeoutMs,
                    readTimeoutMs = readTimeoutMs,
                    callTimeoutMs = callTimeoutMs,
                )
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

    // Resolved from the flavor-provided defaultFavoritesSyncController() (GMS on play, no-op on fdroid).
    fun favoritesSyncController(): FavoritesSyncController = favoritesSyncControllerRef ?: synchronized(this) {
        favoritesSyncControllerRef ?: defaultFavoritesSyncController().also { favoritesSyncControllerRef = it }
    }
}
