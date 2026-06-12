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

    /** Max departure groups rendered on the tile. */
    const val TILE_MAX_GROUPS = 3

    /** Cached tile data older than this triggers an async refresh. */
    const val TILE_REFRESH_THRESHOLD_MS = 30_000L

    /** Tile freshness interval requested from the platform. */
    const val TILE_FRESHNESS_INTERVAL_MS = 60_000L
}
