package dev.catchthenext.phone

import android.content.Context
import dev.catchthenext.android.di.CommonGraph
import dev.catchthenext.phone.liveupdate.LiveUpdateController
import dev.catchthenext.phone.liveupdate.LiveUpdateStateStore

object PhoneGraph : CommonGraph(
    apiKey = BuildConfig.APP_API_KEY,
    baseUrl = BuildConfig.CATCH_THE_NEXT_BASE_URL,
    userAgent = "CatchTheNext/1.0 (Android Phone; +https://codeberg.org/ursidaureus/catch_the_next)",
) {
    @Volatile private var liveUpdateStateStoreRef: LiveUpdateStateStore? = null
    @Volatile private var liveUpdateControllerRef: LiveUpdateController? = null
    @Volatile var pendingDeepLinkStopId: String? = null

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
