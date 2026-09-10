package dev.catchthenext.android.location

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

private const val PASSIVE_CACHE_TTL_MS = 60_000L

object LocationCache {
    private val cached = AtomicReference<LatLon?>(null)
    private val cachedAt = AtomicLong(0L)

    /** When the last high-accuracy (GPS) fallback attempt was started; 0 if never this process. */
    private val gpsAttemptAt = AtomicLong(0L)
    val lastGpsAttemptAt: Long get() = gpsAttemptAt.get()

    fun get(): LatLon? = getFix()?.loc

    /** The cached fix with the time it was stored, or null once past [PASSIVE_CACHE_TTL_MS]. */
    fun getFix(): LocationFix? {
        val loc = cached.get() ?: return null
        val at = cachedAt.get()
        val age = System.currentTimeMillis() - at
        return if (age <= PASSIVE_CACHE_TTL_MS) LocationFix(loc, at) else null
    }

    fun put(loc: LatLon) {
        cached.set(loc)
        cachedAt.set(System.currentTimeMillis())
    }

    fun markGpsAttempt() {
        gpsAttemptAt.set(System.currentTimeMillis())
    }
}
