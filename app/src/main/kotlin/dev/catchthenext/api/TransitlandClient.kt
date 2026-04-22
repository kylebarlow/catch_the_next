package dev.catchthenext.api

import com.google.gson.annotations.SerializedName
import com.google.gson.Gson
import dev.catchthenext.model.Departure
import dev.catchthenext.model.Stop
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.util.concurrent.TimeUnit

class TransitlandClient(
    private val apiKey: String,
    private val baseUrl: String = "http://localhost:39217/api/v2/rest"
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    fun getNearbyStops(lat: Double, lon: Double, radiusMeters: Int = 500, limit: Int = 20): List<Stop> {
        val url = "$baseUrl/stops".toHttpUrl().newBuilder()
            .addQueryParameter("lat", lat.toString())
            .addQueryParameter("lon", lon.toString())
            .addQueryParameter("radius", radiusMeters.toString())
            .addQueryParameter("limit", limit.toString())
            .build()

        val body = executeGet(url.toString())
        val response = gson.fromJson(body, StopsResponse::class.java)
        return response.stops.mapNotNull { it.toStop() }
    }

    fun getDepartures(stopId: Long, nextSeconds: Int = 7200): List<Departure> {
        val url = "$baseUrl/stops/$stopId/departures".toHttpUrl().newBuilder()
            .addQueryParameter("next", nextSeconds.toString())
            .addQueryParameter("relative_date", "TODAY")
            .build()

        val body = executeGet(url.toString())
        val response = gson.fromJson(body, DeparturesResponse::class.java)
        // API wraps results in stops[]; departures live on the stop itself (leaf) or its children (parent station).
        return response.stops
            .flatMap { it.allDepartures() }
            .mapNotNull { it.toDeparture(stopId) }
            .sortedBy { it.departureMinutes }
    }

    private fun executeGet(url: String): String {
        val request = Request.Builder().url(url).addHeader("X-API-Key", apiKey).get().build()
        val response = http.newCall(request).execute()
        if (!response.isSuccessful) {
            throw IOException("API error ${response.code}: ${response.body?.string()?.take(200)}")
        }
        return response.body?.string() ?: throw IOException("Empty response body")
    }

    // ── JSON deserialization types ──────────────────────────────────────────

    private data class StopsResponse(val stops: List<StopJson> = emptyList())

    private data class StopJson(
        val id: Long? = null,
        @SerializedName("stop_id") val stopId: String? = null,
        @SerializedName("stop_name") val stopName: String? = null,
        @SerializedName("onestop_id") val onestopId: String? = null,
        val geometry: GeometryJson? = null
    ) {
        // Returns null for stops missing required fields (Gson can inject null despite non-null defaults).
        fun toStop(): Stop? {
            val resolvedId = id ?: return null
            val resolvedName = stopName ?: return null
            val lon = geometry?.coordinates?.getOrNull(0) ?: 0.0
            val lat = geometry?.coordinates?.getOrNull(1) ?: 0.0
            return Stop(
                id = resolvedId,
                stopId = stopId ?: "",
                stopName = resolvedName,
                lat = lat,
                lon = lon,
                onestopId = onestopId
            )
        }
    }

    private data class GeometryJson(
        val type: String = "",
        val coordinates: List<Double>? = null
    )

    private data class DeparturesResponse(val stops: List<StopWithDeparturesJson> = emptyList())

    private data class StopWithDeparturesJson(
        val departures: List<DepartureJson>? = null,
        val children: List<StopWithDeparturesJson>? = null
    ) {
        fun allDepartures(): List<DepartureJson> =
            (departures ?: emptyList()) + (children ?: emptyList()).flatMap { it.allDepartures() }
    }

    private data class DepartureJson(
        @SerializedName("departure_time") val departureTime: String? = null,
        @SerializedName("stop_headsign") val stopHeadsign: String? = null,
        @SerializedName("schedule_relationship") val scheduleRelationship: String? = null,
        val trip: TripJson? = null,
        val departure: StopTimeEventJson? = null
    ) {
        fun toDeparture(stopId: Long): Departure? {
            val deptTime = departureTime ?: return null
            val minutesFromNow = computeMinutesFromNow(deptTime, departure?.scheduledUtc)
            if (minutesFromNow < 0) return null

            val route = trip?.route
            val headsign = stopHeadsign?.takeIf { it.isNotBlank() }
                ?: trip?.tripHeadsign?.takeIf { it.isNotBlank() }
                ?: ""

            return Departure(
                stopId = stopId,
                departureTime = deptTime,
                departureMinutes = minutesFromNow,
                routeShortName = route?.routeShortName ?: "",
                routeLongName = route?.routeLongName ?: "",
                headsign = headsign,
                scheduleRelationship = scheduleRelationship ?: "SCHEDULED"
            )
        }

        private fun computeMinutesFromNow(departureTimeStr: String, scheduledUtc: String?): Long {
            if (!scheduledUtc.isNullOrBlank()) {
                return try {
                    val instant = Instant.parse(scheduledUtc)
                    Duration.between(Instant.now(), instant).toMinutes()
                } catch (_: Exception) {
                    parseGtfsTimeMinutes(departureTimeStr)
                }
            }
            return parseGtfsTimeMinutes(departureTimeStr)
        }

        // GTFS departure_time can exceed 24:00:00 for post-midnight trips.
        private fun parseGtfsTimeMinutes(timeStr: String): Long {
            val parts = timeStr.split(":")
            val h = parts.getOrNull(0)?.toIntOrNull() ?: return -1
            val m = parts.getOrNull(1)?.toIntOrNull() ?: return -1
            val depTotalMinutes = h * 60 + m
            val now = LocalTime.now()
            val nowMinutes = now.hour * 60 + now.minute
            return (depTotalMinutes - nowMinutes).toLong()
        }
    }

    private data class TripJson(
        @SerializedName("trip_headsign") val tripHeadsign: String? = null,
        val route: RouteJson? = null
    )

    private data class RouteJson(
        @SerializedName("route_short_name") val routeShortName: String? = null,
        @SerializedName("route_long_name") val routeLongName: String? = null,
        @SerializedName("route_type") val routeType: Int? = null
    )

    private data class StopTimeEventJson(
        @SerializedName("scheduled_utc") val scheduledUtc: String? = null,
        @SerializedName("scheduled_local") val scheduledLocal: String? = null,
        @SerializedName("estimated_utc") val estimatedUtc: String? = null,
        @SerializedName("estimated_local") val estimatedLocal: String? = null
    )
}
