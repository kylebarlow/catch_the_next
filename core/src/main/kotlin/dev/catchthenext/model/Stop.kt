package dev.catchthenext.model

data class Stop(
    val id: Long,
    val stopId: String,
    val stopName: String,
    val lat: Double,
    val lon: Double,
    val onestopId: String? = null,
    val feed: FeedAttribution? = null,
    /** Route short names served by this stop, as reported by the server's stop search. */
    val routesServed: List<String>? = null,
    /** User-chosen display name for a favorite ("Work → home"); null = use [stopName]. */
    val nickname: String? = null,
    /** Per-favorite route filter: only these route short names are shown. Null/empty = show all. */
    val shownRoutes: List<String>? = null,
    /** Manual position among favorites; null = unordered (sorts after ordered entries). */
    val sortOrder: Int? = null,
    /** Manual walk-time override in minutes for "leave now" nudges; null = estimate from distance. */
    val walkMinutesOverride: Int? = null,
) {
    val displayName: String get() = nickname?.takeIf { it.isNotBlank() } ?: stopName

    /** Whether departures for [routeShortName] should be shown under this favorite's filter. */
    fun showsRoute(routeShortName: String): Boolean =
        shownRoutes.isNullOrEmpty() || routeShortName in shownRoutes

    fun displayString(): String = "$stopName  [ID: $id | GTFS: $stopId]"
}

/** Manual favorites order: explicit [Stop.sortOrder] first (ascending), unordered entries after. */
fun List<Stop>.inFavoriteOrder(): List<Stop> =
    sortedWith(compareBy(nullsLast()) { it.sortOrder })
