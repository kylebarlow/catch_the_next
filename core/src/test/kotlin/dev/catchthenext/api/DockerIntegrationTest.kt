package dev.catchthenext.api

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DockerIntegrationTest {

    private val baseUrl = "http://localhost:39217/api/v2/rest"
    private val healthUrl = "http://localhost:39217/healthz"
    private val apiKey = "dev-app-key"

    // Mission Bay / China Basin area, SF
    private val testLat = 37.7766595
    private val testLon = -122.3946275

    // Caltrain 4th and King: parent station and a child platform stop
    private val caltrainStopId = 2173133854L
    private val caltrainSouthboundPlatformId = 2173134179L

    private val serverDir: File by lazy {
        // Gradle runs tests with CWD = app/
        File("../server").canonicalFile.also {
            check(it.isDirectory) { "Expected server dir at $it" }
        }
    }

    @BeforeAll
    fun startDockerIfNeeded() {
        if (isServerHealthy()) {
            println("[DockerIntegration] Server already up — skipping docker compose up")
            return
        }

        println("[DockerIntegration] Starting docker compose in $serverDir …")
        val proc = ProcessBuilder("docker", "compose", "up", "-d", "--build")
            .directory(serverDir)
            .redirectErrorStream(true)
            .start()

        val output = proc.inputStream.bufferedReader().readText()
        val finished = proc.waitFor(120, TimeUnit.SECONDS)
        check(finished && proc.exitValue() == 0) {
            "docker compose up failed (exit ${if (finished) proc.exitValue() else "timeout"}):\n$output"
        }

        val deadline = System.currentTimeMillis() + 60_000
        while (!isServerHealthy()) {
            check(System.currentTimeMillis() < deadline) {
                "Server at $healthUrl did not become healthy within 60 s after docker compose up"
            }
            Thread.sleep(1_000)
        }
        println("[DockerIntegration] Server is healthy")
    }

    private fun isServerHealthy(): Boolean = try {
        OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .build()
            .newCall(Request.Builder().url(healthUrl).get().build())
            .execute()
            .use { it.isSuccessful }
    } catch (_: Exception) {
        false
    }

    @Test
    fun `stops API returns stops for known location through docker`() {
        val client = TransitlandClient(apiKey, baseUrl)
        val stops = client.getNearbyStops(testLat, testLon, radiusMeters = 500)

        println("\n[Docker] Stops near $testLat, $testLon:")
        stops.forEach { println("  ID: ${it.id}  GTFS: ${it.stopId}  Name: ${it.stopName}") }

        assertTrue(stops.isNotEmpty(), "Expected at least one stop near Mission Bay, SF")
        assertTrue(stops.all { it.id > 0 }, "All stops must have a valid non-zero ID")
        assertTrue(stops.all { it.stopName.isNotBlank() }, "All stops must have a name")
    }

    @Test
    fun `departures API returns departures for Caltrain 4th and King through docker`() {
        val client = TransitlandClient(apiKey, baseUrl)
        val departures = client.getDepartures(caltrainStopId).departures

        println("\n[Docker] Departures for Caltrain 4th & King (ID: $caltrainStopId):")
        departures.forEach { d ->
            println("  ${d.displayDepartureMinutes} min | Route ${d.routeShortName} → ${d.headsign} (${d.displayDepartureTime})")
        }

        assertTrue(departures.isNotEmpty(), "Expected departures for Caltrain 4th & King")
        assertTrue(departures.all { it.displayDepartureMinutes >= 0 }, "No past departures should be returned")
        assertTrue(
            departures.all { it.routeShortName.isNotBlank() || it.routeLongName.isNotBlank() },
            "All departures should have route info"
        )
    }

    @Test
    fun `departures API handles child platform stop with null children field`() {
        // Platform stops return "children": null from Transitland, which previously caused a 500.
        val client = TransitlandClient(apiKey, baseUrl)
        val departures = client.getDepartures(caltrainSouthboundPlatformId).departures

        println("\n[Docker] Departures for Caltrain Southbound platform (ID: $caltrainSouthboundPlatformId):")
        departures.forEach { d ->
            println("  ${d.displayDepartureMinutes} min | Route ${d.routeShortName} → ${d.headsign}")
        }

        assertTrue(departures.isNotEmpty(), "Expected departures for the Caltrain Southbound platform")
        assertTrue(departures.all { it.displayDepartureMinutes >= 0 }, "No past departures should be returned")
    }
}
