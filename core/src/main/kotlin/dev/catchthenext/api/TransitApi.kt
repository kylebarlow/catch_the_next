package dev.catchthenext.api

import dev.catchthenext.model.Departure
import dev.catchthenext.model.Stop

interface TransitApi {
    fun getNearbyStops(lat: Double, lon: Double, radiusMeters: Int = 600, limit: Int = 20): List<Stop>
    fun getDepartures(stopId: Long, nextSeconds: Int = 7200): List<Departure>
    fun getDeparturesBatch(stopIds: List<Long>, nextSeconds: Int = 7200): Map<Long, List<Departure>>
}
