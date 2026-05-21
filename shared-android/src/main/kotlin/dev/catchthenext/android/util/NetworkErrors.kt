package dev.catchthenext.android.util

import java.net.UnknownHostException

fun Throwable.toNetworkMessage(): String = when (this) {
    is UnknownHostException -> "Network error"
    else -> message ?: "Network error"
}
