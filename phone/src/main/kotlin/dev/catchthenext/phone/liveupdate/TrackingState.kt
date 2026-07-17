package dev.catchthenext.phone.liveupdate

data class TrackingState(
    val onestopId: String?,
    val stopId: Long,
    val stopName: String,
    val stopLat: Double,
    val stopLon: Double,
    val startedAt: Long,
    val firstDepartureEtaEpochMs: Long?,
    val gotWithin100m: Boolean,
    /** Manual walk-time override in minutes; null = estimate from live distance (~80 m/min). */
    val walkMinutesOverride: Int? = null,
)
