package dev.catchthenext.wear.location

import dev.catchthenext.model.Stop
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLon(val lat: Double, val lon: Double)

fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6_371_000.0
    val phi1 = Math.toRadians(lat1)
    val phi2 = Math.toRadians(lat2)
    val dPhi = Math.toRadians(lat2 - lat1)
    val dLambda = Math.toRadians(lon2 - lon1)
    val a = sin(dPhi / 2).pow(2) + cos(phi1) * cos(phi2) * sin(dLambda / 2).pow(2)
    return r * 2 * atan2(sqrt(a), sqrt(1 - a))
}

fun List<Stop>.closestTo(lat: Double, lon: Double): Stop? =
    minByOrNull { haversineMeters(lat, lon, it.lat, it.lon) }
