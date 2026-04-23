package dev.catchthenext.api

import com.google.gson.annotations.SerializedName
import com.google.gson.Gson
import dev.catchthenext.model.Departure
import dev.catchthenext.model.Stop
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
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

    fun getNearbyStops(lat: Double, lon: Double, radiusMeters: Int = 600, limit: Int = 20): List<Stop> {
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
        return response.departures
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

    // Proxy pre-computes departure_minutes and flattens the stop hierarchy.
    private data class DeparturesResponse(val departures: List<ProxyDepartureJson> = emptyList())

    private data class ProxyDepartureJson(
        @SerializedName("route_short_name") val routeShortName: String? = null,
        val headsign: String? = null,
        @SerializedName("departure_minutes") val departureMinutes: Long? = null,
        @SerializedName("departure_time") val departureTime: String? = null,
        @SerializedName("schedule_relationship") val scheduleRelationship: String? = null
    ) {
        fun toDeparture(stopId: Long): Departure? {
            val minutes = departureMinutes ?: return null
            if (minutes < 0) return null
            return Departure(
                stopId = stopId,
                departureTime = departureTime ?: "",
                departureMinutes = minutes,
                routeShortName = routeShortName ?: "",
                routeLongName = "",
                headsign = headsign ?: "",
                scheduleRelationship = scheduleRelationship ?: "SCHEDULED"
            )
        }
    }
}
