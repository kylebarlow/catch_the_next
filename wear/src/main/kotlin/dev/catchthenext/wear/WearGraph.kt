package dev.catchthenext.wear

import dev.catchthenext.android.di.CommonGraph

object WearGraph : CommonGraph(
    apiKey = BuildConfig.APP_API_KEY,
    baseUrl = BuildConfig.CATCH_THE_NEXT_BASE_URL,
)
