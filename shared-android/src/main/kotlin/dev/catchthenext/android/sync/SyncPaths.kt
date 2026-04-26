package dev.catchthenext.android.sync

import android.net.Uri

const val PATH_FAVORITES = "/favorites"
val URI_FAVORITES: Uri = Uri.Builder().scheme("wear").authority("*").path(PATH_FAVORITES).build()
