package dev.catchthenext.api

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class TransitlandClientTest {

    // Stop IDs found at this location — update if they change:
    // 37.7766595, -122.3946275 (Mission Bay / China Basin area, SF)
    private val testLat = 37.7766595
    private val testLon = -122.3946275
    private val testRadius = 500

    private fun apiKey(): String {
        return System.getenv("TRANSITLAND_API_KEY")
            ?: run {
                val dotenvFile = java.io.File(".env")
                if (dotenvFile.exists()) {
                    dotenvFile.readLines()
                        .firstOrNull { it.startsWith("TRANSITLAND_API_KEY=") }
                        ?.removePrefix("TRANSITLAND_API_KEY=")
                        ?.trim()
                } else null
            } ?: ""
    }

    @Test
    fun `getNearbyStops returns stops for known location`() {
        val key = apiKey()
        assumeTrue(key.isNotBlank(), "TRANSITLAND_API_KEY not set — skipping live API test")

        val client = TransitlandClient(key)
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
    fun `getDepartures returns departures for Caltrain 4th and King`() {
        val key = apiKey()
        assumeTrue(key.isNotBlank(), "TRANSITLAND_API_KEY not set — skipping live API test")

        // Parent station — departures live on child platform stops in the API response.
        val caltrainStopId = 2173133854L
        val client = TransitlandClient(key)
        val departures = client.getDepartures(caltrainStopId)

        println("\nDepartures for Caltrain 4th & King (ID: $caltrainStopId):")
        departures.forEach { d ->
            println("  ${d.departureMinutes} min | Route ${d.routeShortName} → ${d.headsign} (${d.departureTime})")
        }

        assertTrue(departures.isNotEmpty(), "Expected departures for a major Caltrain station")
        assertTrue(departures.all { it.departureMinutes >= 0 }, "No past departures should be returned")
        assertTrue(departures.all { it.routeShortName.isNotBlank() || it.routeLongName.isNotBlank() },
            "All departures should have route info")
    }
}
