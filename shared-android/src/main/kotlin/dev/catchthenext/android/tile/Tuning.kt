package dev.catchthenext.android.tile

/**
 * Client-side cache / radius / limit knobs. The server holds its own half of these in
 * `server/config.py`; the two are kept in sync by hand.
 */
object Tuning {
    /** Per-stop departure cache freshness window. */
    const val CACHE_TTL_MS = 60_000L

    /**
     * Max favorites fetched in one batch departures call. The server allows 6
     * (`BATCH_MAX_STOPS` in server/config.py) — the client deliberately stays under that as
     * headroom. Keep client <= server.
     */
    const val MAX_BATCH_STOPS = 4

    /** Default "nearby" threshold (1 mile) when the user hasn't set one. */
    const val DEFAULT_THRESHOLD_METERS = 1609

    /** Radius used when re-resolving a stale favorite's rotated stop ID. */
    const val STALE_RESOLVE_RADIUS_M = 100

    /** Radius for the auto (current-location) Add Stop search. */
    const val ADD_STOP_AUTO_RADIUS_M = 600

    /** Radius for a map-pin Add Stop search. */
    const val ADD_STOP_SEARCH_RADIUS_M = 1500

    /**
     * Upper bound on departure groups rendered on the tile. The wear module's `TileFit` lowers
     * it per device when the screen or the user's font scale cannot hold that many two-line rows.
     */
    const val TILE_MAX_GROUPS = 3

    /** Cached tile data older than this triggers an async refresh. */
    const val TILE_REFRESH_THRESHOLD_MS = 30_000L

    /**
     * Tile freshness interval requested from the platform. 60 s matches 511's realtime cadence.
     * Must stay >= the server's `RESPONSE_CACHE_TTL` (server/config.py, currently 50 s) for
     * consecutive tile polls to hit the server's response cache; the server side should raise
     * its TTL to 60 s or more.
     */
    const val TILE_FRESHNESS_INTERVAL_MS = 60_000L

    /**
     * How far back [DeparturesPipeline.quickCacheRead] will reach for a first paint. Anything
     * newer than this is worth showing (with an "Nm ago" label) instead of a spinner; entries
     * older than [CACHE_TTL_MS] are flagged `isStale`.
     */
    const val QUICK_CACHE_MAX_AGE_MS = 6 * 60 * 60 * 1000L

    /** Ignore non-forced ViewModel refreshes this soon after the last completed run. */
    const val VM_REFRESH_DEBOUNCE_MS = 15_000L

    /** Max timeline entries emitted per tile (one per upcoming departure instant, plus terminal). */
    const val TILE_MAX_TIMELINE_SLICES = 8

    /** A departure keeps showing as "Now" for this long after its instant. */
    const val TILE_NOW_GRACE_MS = 60_000L

    /**
     * Timeout for a PASSIVE fresh-fix attempt once last-known is unusable. Since package J the
     * tile and the departures screen render from the quick/persisted location first and only
     * re-render if the fresh fix changes the stop selection, so this no longer gates any paint:
     * only the first-run "no location anywhere" path and `FavoritesViewModel`'s initial distance
     * lookup wait on it, and both already tolerate a null or late result. 15 s gives a watch
     * relaying through a phone enough time to answer.
     */
    const val PASSIVE_LOCATION_TIMEOUT_MS = 15_000L

    /**
     * Timeout for the wear-only high-accuracy (GPS) fallback tried after a balanced-power fix
     * came back empty. Runs after the first render, so it costs latency only for the refine pass.
     */
    const val GPS_FALLBACK_TIMEOUT_MS = 15_000L

    /**
     * The GPS fallback is skipped when the OS already holds a last-known fix younger than this
     * (read from the OS, so it survives process death).
     */
    const val GPS_FALLBACK_MIN_FIX_AGE_MS = 10 * 60 * 1000L

    /** Minimum spacing between GPS fallback attempts — the battery guard on the whole feature. */
    const val GPS_FALLBACK_MIN_INTERVAL_MS = 5 * 60 * 1000L

    // OkHttp budgets for the wear app. The uncached server path is ~10 s today; drop these to
    // 10 / 12 / 15 s once the server's 511 realtime rebuild moves out of the request path.
    const val WEAR_CONNECT_TIMEOUT_MS = 10_000L
    const val WEAR_READ_TIMEOUT_MS = 20_000L
    const val WEAR_CALL_TIMEOUT_MS = 25_000L
}
