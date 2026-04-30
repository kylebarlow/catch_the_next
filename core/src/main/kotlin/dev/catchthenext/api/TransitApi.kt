package dev.catchthenext.api

import dev.catchthenext.model.Alert
import dev.catchthenext.model.Departure
import dev.catchthenext.model.Stop

data class StopDepartures(
    val stopId: Long,
    val departures: List<Departure>,
    val alerts: List<Alert> = emptyList(),
)

interface TransitApi {
    fun getNearbyStops(lat: Double, lon: Double, radiusMeters: Int = 600, limit: Int = 20): List<Stop>
    fun getDepartures(stopId: Long, nextSeconds: Int = 7200): StopDepartures
    fun getDeparturesBatch(stopIds: List<Long>, nextSeconds: Int = 7200): Map<Long, StopDepartures>
}
