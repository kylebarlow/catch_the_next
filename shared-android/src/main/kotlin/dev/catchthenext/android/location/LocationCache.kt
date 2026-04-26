package dev.catchthenext.android.location

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

private const val PASSIVE_CACHE_TTL_MS = 60_000L

object LocationCache {
    private val cached = AtomicReference<LatLon?>(null)
    private val cachedAt = AtomicLong(0L)

    fun get(): LatLon? {
        val loc = cached.get() ?: return null
        val age = System.currentTimeMillis() - cachedAt.get()
        return if (age <= PASSIVE_CACHE_TTL_MS) loc else null
    }

    fun put(loc: LatLon) {
        cached.set(loc)
        cachedAt.set(System.currentTimeMillis())
    }
}
