package dev.catchthenext.api

import com.google.gson.annotations.SerializedName
import dev.catchthenext.json.AppJson
import dev.catchthenext.model.Alert
import dev.catchthenext.model.AlertActivePeriod
import dev.catchthenext.model.AlertSeverity
import dev.catchthenext.model.Departure
import dev.catchthenext.model.DepartureTimeSource
import dev.catchthenext.model.FeedAttribution
import dev.catchthenext.model.Place
import dev.catchthenext.model.Stop
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class TransitlandClient(
    private val apiKey: String,
    private val baseUrl: String = "http://localhost:39217/api/v2/rest",
    private val userAgent: String = "CatchTheNext/1.0 (Android; +https://codeberg.org/ursidaureus/catch_the_next)",
) : TransitApi {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val req = chain.request().newBuilder()
                .header("User-Agent", userAgent)
                .build()
            chain.proceed(req)
        }
        .build()

    private val gson = AppJson.gson
    private val inFlight = ConcurrentHashMap<String, CompletableFuture<Any>>()

    @Suppress("UNCHECKED_CAST")
    private fun <T> dedupe(key: String, supplier: () -> T): T {
        val future = CompletableFuture<Any>()
        val existing = inFlight.putIfAbsent(key, future)
        if (existing != null) return existing.get() as T
        return try {
            val result = supplier()
            future.complete(result)
            result
        } catch (e: Exception) {
            future.completeExceptionally(e)
            throw e
        } finally {
            inFlight.remove(key, future)
        }
    }

    override fun getNearbyStops(lat: Double, lon: Double, radiusMeters: Int, limit: Int): List<Stop> =
        dedupe("stops:$lat:$lon:$radiusMeters:$limit") {
            val url = "$baseUrl/stops".toHttpUrl().newBuilder()
                .addQueryParameter("lat", lat.toString())
                .addQueryParameter("lon", lon.toString())
                .addQueryParameter("radius", radiusMeters.toString())
                .addQueryParameter("limit", limit.toString())
                .build()
            val body = executeGet(url.toString())
            val response = gson.fromJson(body, StopsResponse::class.java)
            response.stops.mapNotNull { it.toStop(fallbackLat = lat, fallbackLon = lon) }
        }

    override fun getDepartures(stopId: Long, nextSeconds: Int): StopDepartures =
        dedupe("dep:$stopId:$nextSeconds") {
            val url = "$baseUrl/stops/$stopId/departures".toHttpUrl().newBuilder()
                .addQueryParameter("next", nextSeconds.toString())
                .addQueryParameter("relative_date", "TODAY")
                .build()
            val body = executeGet(url.toString())
            val response = gson.fromJson(body, DeparturesResponse::class.java)
            val departures = response.departures
                .mapNotNull { it.toDeparture(stopId) }
                .sortedBy { it.displayDepartureMinutes }
            val alerts = response.alerts?.mapNotNull { it.toAlert() } ?: emptyList()
            StopDepartures(stopId, departures, alerts)
        }

    override fun geocodePlace(query: String, focusLat: Double?, focusLon: Double?, limit: Int): List<Place> =
        dedupe("geo:$query:$focusLat:$focusLon:$limit") {
            val urlBuilder = "$baseUrl/geocode".toHttpUrl().newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("limit", limit.toString())
            if (focusLat != null && focusLon != null) {
                urlBuilder
                    .addQueryParameter("focus_lat", focusLat.toString())
                    .addQueryParameter("focus_lon", focusLon.toString())
            }
            val body = executeGet(urlBuilder.build().toString())
            val response = gson.fromJson(body, PlacesResponse::class.java)
            response.places.map { it.toPlace() }
        }

    override fun getDeparturesBatch(onestopIds: List<String>, nextSeconds: Int): Map<String, StopDepartures> {
        if (onestopIds.isEmpty()) return emptyMap()
        val key = "depbatch:${onestopIds.sorted().joinToString(",")}:$nextSeconds"
        return dedupe(key) {
            val url = "$baseUrl/departures".toHttpUrl().newBuilder()
                .addQueryParameter("onestop_ids", onestopIds.joinToString(","))
                .addQueryParameter("next", nextSeconds.toString())
                .build()
            val body = executeGet(url.toString())
            val response = gson.fromJson(body, BatchDeparturesResponse::class.java)
            response.stops.mapNotNull { batchStop ->
                val onestopId = batchStop.onestopId ?: return@mapNotNull null
                val deps = batchStop.departures.mapNotNull { it.toDeparture(0L) }
                    .sortedBy { it.displayDepartureMinutes }
                val alerts = batchStop.alerts?.mapNotNull { it.toAlert() } ?: emptyList()
                Pair(onestopId, StopDepartures(0L, deps, alerts))
            }.toMap()
        }
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

    private data class PlacesResponse(val places: List<PlaceJson> = emptyList())

    private data class PlaceJson(
        @SerializedName("place_id") val placeId: String? = null,
        @SerializedName("display_name") val displayName: String? = null,
        val lat: Double? = null,
        val lon: Double? = null,
        val category: String? = null,
        val type: String? = null,
    ) {
        fun toPlace() = Place(
            placeId = placeId ?: "",
            displayName = displayName ?: "",
            lat = lat ?: 0.0,
            lon = lon ?: 0.0,
            category = category,
            type = type,
        )
    }

    private data class StopsResponse(val stops: List<StopJson> = emptyList())

    private data class StopJson(
        val id: Long? = null,
        @SerializedName("stop_id") val stopId: String? = null,
        @SerializedName("stop_name") val stopName: String? = null,
        @SerializedName("onestop_id") val onestopId: String? = null,
        val geometry: GeometryJson? = null,
        // Feed attribution fields (flattened by proxy)
        @SerializedName("feed_onestop_id") val feedOnestopId: String? = null,
        @SerializedName("feed_name") val feedName: String? = null,
        @SerializedName("attribution_text") val attributionText: String? = null,
        @SerializedName("attribution_instructions") val attributionInstructions: String? = null,
        @SerializedName("use_without_attribution") val useWithoutAttribution: Boolean = true,
        @SerializedName("license_spdx") val licenseSpdx: String? = null,
        @SerializedName("license_url") val licenseUrl: String? = null,
        @SerializedName("routes_served") val routesServed: List<String>? = null,
    ) {
        // Returns null for stops missing required fields (Gson can inject null despite non-null defaults).
        fun toStop(fallbackLat: Double? = null, fallbackLon: Double? = null): Stop? {
            val resolvedId = id ?: return null
            val resolvedName = stopName ?: return null
            val coords = geometry?.coordinates
            val lon = coords?.getOrNull(0) ?: fallbackLon ?: return null
            val lat = coords?.getOrNull(1) ?: fallbackLat ?: return null
            val feed = feedOnestopId?.let {
                FeedAttribution(
                    feedOnestopId = it,
                    feedName = feedName,
                    attributionText = attributionText,
                    attributionInstructions = attributionInstructions,
                    useWithoutAttribution = useWithoutAttribution,
                    licenseSpdx = licenseSpdx,
                    licenseUrl = licenseUrl
                )
            }
            return Stop(
                id = resolvedId,
                stopId = stopId ?: "",
                stopName = resolvedName,
                lat = lat,
                lon = lon,
                onestopId = onestopId,
                feed = feed,
                routesServed = routesServed?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }
            )
        }
    }

    private data class GeometryJson(
        val type: String = "",
        val coordinates: List<Double>? = null
    )

    // Proxy pre-computes departure_minutes and flattens the stop hierarchy.
    private data class DeparturesResponse(
        val departures: List<ProxyDepartureJson> = emptyList(),
        val alerts: List<AlertJson>? = null,
    )

    private data class BatchDeparturesResponse(val stops: List<BatchStopDeparturesJson> = emptyList())

    private data class BatchStopDeparturesJson(
        @SerializedName("onestop_id") val onestopId: String? = null,
        val departures: List<ProxyDepartureJson> = emptyList(),
        val alerts: List<AlertJson>? = null,
    )

    private data class AlertActivePeriodJson(
        val start: Long? = null,
        val end: Long? = null,
    )

    private data class AlertJson(
        val cause: String? = null,
        val effect: String? = null,
        @SerializedName("severity_level") val severityLevel: String? = null,
        @SerializedName("header_text") val headerText: String? = null,
        @SerializedName("description_text") val descriptionText: String? = null,
        @SerializedName("tts_header_text") val ttsHeaderText: String? = null,
        @SerializedName("tts_description_text") val ttsDescriptionText: String? = null,
        val url: String? = null,
        @SerializedName("active_period") val activePeriod: List<AlertActivePeriodJson>? = null,
    ) {
        fun toAlert(): Alert? {
            val severity = when (severityLevel?.uppercase()) {
                "INFO" -> AlertSeverity.INFO
                "WARNING" -> AlertSeverity.WARNING
                "SEVERE" -> AlertSeverity.SEVERE
                else -> AlertSeverity.UNKNOWN_SEVERITY
            }
            return Alert(
                cause = cause,
                effect = effect,
                severityLevel = severity,
                headerText = headerText,
                descriptionText = descriptionText,
                ttsHeaderText = ttsHeaderText,
                ttsDescriptionText = ttsDescriptionText,
                url = url,
                activePeriod = activePeriod?.map { AlertActivePeriod(it.start, it.end) } ?: emptyList(),
            )
        }
    }

    private data class ProxyDepartureJson(
        @SerializedName("route_short_name") val routeShortName: String? = null,
        val headsign: String? = null,
        @SerializedName("scheduled_departure_time") val scheduledDepartureTime: String? = null,
        @SerializedName("scheduled_departure_utc") val scheduledDepartureUtc: String? = null,
        @SerializedName("scheduled_departure_minutes") val scheduledDepartureMinutes: Long? = null,
        @SerializedName("live_departure_time") val liveDepartureTime: String? = null,
        @SerializedName("live_departure_utc") val liveDepartureUtc: String? = null,
        @SerializedName("live_departure_minutes") val liveDepartureMinutes: Long? = null,
        @SerializedName("time_source") val timeSource: String? = null,
        @SerializedName("schedule_relationship") val scheduleRelationship: String? = null,
        @SerializedName("agency_name") val agencyName: String? = null,
        @SerializedName("feed_onestop_id") val feedOnestopId: String? = null,
        @SerializedName("feed_name") val feedName: String? = null,
        @SerializedName("attribution_text") val attributionText: String? = null,
        @SerializedName("attribution_instructions") val attributionInstructions: String? = null,
        @SerializedName("use_without_attribution") val useWithoutAttribution: Boolean = true,
        @SerializedName("license_spdx") val licenseSpdx: String? = null,
        @SerializedName("license_url") val licenseUrl: String? = null,
    ) {
        fun toDeparture(stopId: Long): Departure? {
            val parsedSource = when (timeSource) {
                "LIVE" -> DepartureTimeSource.LIVE
                else -> DepartureTimeSource.SCHEDULED
            }

            val displayMinutes = when (parsedSource) {
                DepartureTimeSource.LIVE -> liveDepartureMinutes ?: scheduledDepartureMinutes
                DepartureTimeSource.SCHEDULED -> scheduledDepartureMinutes
            } ?: return null

            if (displayMinutes < 0) return null

            val displayTime = when (parsedSource) {
                DepartureTimeSource.LIVE -> liveDepartureTime ?: scheduledDepartureTime ?: ""
                DepartureTimeSource.SCHEDULED -> scheduledDepartureTime ?: ""
            }

            val feed = feedOnestopId?.let {
                FeedAttribution(
                    feedOnestopId = it,
                    feedName = feedName,
                    attributionText = attributionText,
                    attributionInstructions = attributionInstructions,
                    useWithoutAttribution = useWithoutAttribution,
                    licenseSpdx = licenseSpdx,
                    licenseUrl = licenseUrl
                )
            }
            return Departure(
                stopId = stopId,
                scheduledDepartureTime = scheduledDepartureTime,
                scheduledDepartureMinutes = scheduledDepartureMinutes,
                liveDepartureTime = liveDepartureTime,
                liveDepartureMinutes = liveDepartureMinutes,
                displayDepartureTime = displayTime,
                displayDepartureMinutes = displayMinutes,
                timeSource = parsedSource,
                routeShortName = routeShortName ?: "",
                routeLongName = "",
                headsign = headsign ?: "",
                scheduleRelationship = scheduleRelationship ?: "SCHEDULED",
                agencyName = agencyName,
                feed = feed
            )
        }
    }
}
