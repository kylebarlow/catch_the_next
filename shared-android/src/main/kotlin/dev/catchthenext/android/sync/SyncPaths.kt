package dev.catchthenext.android.sync

import android.net.Uri

const val PATH_FAVORITES = "/favorites/v2"
val URI_FAVORITES: Uri = Uri.Builder().scheme("wear").authority("*").path(PATH_FAVORITES).build()

const val CAPABILITY_FAVORITES_SYNC = "favorites_sync_v1"
const val KEY_ITEMS = "items"
