package dev.catchthenext.api

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeocodePlaceTest {

    private val PLACES_JSON = """
        {"places":[
          {"place_id":"123456","display_name":"Berkeley, CA","lat":37.87,"lon":-122.27,
           "category":"boundary","type":"administrative"}
        ]}
    """.trimIndent()

    @Test
    fun `geocodePlace returns parsed places`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(PLACES_JSON).setResponseCode(200))
        server.start()
        val client = TransitlandClient("test-key", server.url("/api/v2/rest").toString())

        val places = client.geocodePlace("Berkeley CA")

        assertEquals(1, places.size)
        val place = places[0]
        assertEquals("123456", place.placeId)
        assertEquals("Berkeley, CA", place.displayName)
        assertEquals(37.87, place.lat, 0.001)
        assertEquals(-122.27, place.lon, 0.001)
        assertEquals("boundary", place.category)
        assertEquals("administrative", place.type)
        server.shutdown()
    }

    @Test
    fun `geocodePlace handles empty results`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"places":[]}""").setResponseCode(200))
        server.start()
        val client = TransitlandClient("test-key", server.url("/api/v2/rest").toString())

        val places = client.geocodePlace("xyzzy-nonexistent-99999")

        assertTrue(places.isEmpty())
        server.shutdown()
    }

    @Test
    fun `geocodePlace passes focus params in URL`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"places":[]}""").setResponseCode(200))
        server.start()
        val client = TransitlandClient("test-key", server.url("/api/v2/rest").toString())

        client.geocodePlace("station", focusLat = 37.8, focusLon = -122.3)

        val request = server.takeRequest()
        assertTrue("focus_lat" in request.path!!)
        assertTrue("focus_lon" in request.path!!)
        server.shutdown()
    }

    @Test
    fun `geocodePlace throws on upstream error`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(502))
        server.start()
        val client = TransitlandClient("test-key", server.url("/api/v2/rest").toString())

        val result = runCatching { client.geocodePlace("Berkeley") }

        assertTrue(result.isFailure)
        server.shutdown()
    }
}


class TransitlandClientTest {

    // Stop IDs found at this location — update if they change:
    // 37.7766595, -122.3946275 (Mission Bay / China Basin area, SF)
    private val testLat = 37.7766595
    private val testLon = -122.3946275
    private val testRadius = 500

    private fun apiKey(): String =
        System.getenv("APP_API_KEYS")?.split(",")?.firstOrNull()?.trim()
            ?: run {
                val dotenvFile = java.io.File(".env")
                if (dotenvFile.exists()) {
                    dotenvFile.readLines()
                        .firstOrNull { it.startsWith("APP_API_KEYS=") }
                        ?.removePrefix("APP_API_KEYS=")
                        ?.split(",")?.firstOrNull()?.trim()
                } else null
            } ?: ""

    private fun baseUrl(): String =
        System.getenv("CATCH_THE_NEXT_BASE_URL") ?: "http://localhost:39217/api/v2/rest"

    @Test
    fun `getNearbyStops returns stops for known location`() {
        val key = apiKey()
        assumeTrue(key.isNotBlank(), "APP_API_KEY not set — skipping live API test")

        val client = TransitlandClient(key, baseUrl())
        val stops = client.getNearbyStops(testLat, testLon, testRadius)

        println("\nStops found near $testLat, $testLon (radius ${testRadius}m):")
        stops.forEach { stop ->
            println("  ID: ${stop.id}  GTFS: ${stop.stopId}  Name: ${stop.stopName}")
        }

        assertTrue(stops.isNotEmpty(), "Expected at least one stop near the test location")
        assertTrue(stops.all { it.id > 0 }, "All stops should have a valid non-zero ID")
        assertTrue(stops.all { it.stopName.isNotBlank() }, "All stops should have a name")
    }

    @Test
    fun `getDeparturesBatch returns departures for Caltrain 4th and King`() {
        val key = apiKey()
        assumeTrue(key.isNotBlank(), "APP_API_KEYS not set — skipping live API test")

        val caltrainOnestopId = "s-9q8yyv4b0b-caltrain4th~king"
        val client = TransitlandClient(key, baseUrl())
        val result = client.getDeparturesBatch(listOf(caltrainOnestopId))
        val departures = result[caltrainOnestopId]?.departures ?: emptyList()

        println("\nDepartures for Caltrain 4th & King ($caltrainOnestopId):")
        departures.forEach { d ->
            println("  ${d.displayDepartureMinutes} min | Route ${d.routeShortName} → ${d.headsign} (${d.displayDepartureTime})")
        }

        assertTrue(departures.isNotEmpty(), "Expected departures for a major Caltrain station")
        assertTrue(departures.all { it.displayDepartureMinutes >= 0 }, "No past departures should be returned")
        assertTrue(departures.all { it.routeShortName.isNotBlank() || it.routeLongName.isNotBlank() },
            "All departures should have route info")
    }
}
