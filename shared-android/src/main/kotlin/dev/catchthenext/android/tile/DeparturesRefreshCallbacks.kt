package dev.catchthenext.android.tile

import android.content.Context
import java.util.concurrent.CopyOnWriteArrayList

object DeparturesRefreshCallbacks {
    private val callbacks = CopyOnWriteArrayList<suspend (Context) -> Unit>()

    fun register(cb: suspend (Context) -> Unit) {
        callbacks += cb
    }

    suspend fun fireAll(ctx: Context) {
        callbacks.forEach { runCatching { it(ctx) } }
    }
}
