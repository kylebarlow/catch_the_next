package dev.catchthenext.android.sync

import android.net.Uri

const val PATH_FAVORITES = "/favorites"
val URI_FAVORITES: Uri = Uri.Builder().scheme("wear").authority("*").path(PATH_FAVORITES).build()

const val CAPABILITY_FAVORITES_SYNC = "favorites_sync_v1"
const val PATH_SYNC_REQUEST_REPUBLISH = "/sync/request-republish"
