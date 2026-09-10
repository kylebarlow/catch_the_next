package dev.catchthenext.wear

import dev.catchthenext.android.di.CommonGraph
import dev.catchthenext.android.tile.Tuning

object WearGraph : CommonGraph(
    apiKey = BuildConfig.APP_API_KEY,
    baseUrl = BuildConfig.CATCH_THE_NEXT_BASE_URL,
    // A slow server must not pin the watch's spinner for OkHttp's default 30 s read timeout.
    connectTimeoutMs = Tuning.WEAR_CONNECT_TIMEOUT_MS,
    readTimeoutMs = Tuning.WEAR_READ_TIMEOUT_MS,
    callTimeoutMs = Tuning.WEAR_CALL_TIMEOUT_MS,
)
