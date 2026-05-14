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

    // Mission Bay / China Basin area, SF
    private val testLat = 37.7766595
    private val testLon = -122.3946275

    // Stable onestop IDs for Caltrain 4th & King (parent + platform stops)
    private val caltrainOnestopId = "s-9q8yyv4b0b-caltrain4th~king"
    private val caltrainSouthboundOnestopId = "s-9q8yyufxpn-sanfranciscocaltrainstationsouthbound"

    private val serverDir: File by lazy {
        // Gradle runs tests with CWD = core/
        File("../server").canonicalFile.also {
            check(it.isDirectory) { "Expected server dir at $it" }
        }
    }

    private fun apiKey(): String =
        System.getenv("APP_API_KEYS")?.split(",")?.firstOrNull()?.trim()
            ?: File(serverDir, ".env").takeIf { it.exists() }
                ?.readLines()
                ?.firstOrNull { it.startsWith("APP_API_KEYS=") }
                ?.removePrefix("APP_API_KEYS=")
                ?.split(",")?.firstOrNull()?.trim()
            ?: ""

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
        val client = TransitlandClient(apiKey(), baseUrl)
        val stops = client.getNearbyStops(testLat, testLon, radiusMeters = 500)

        println("\n[Docker] Stops near $testLat, $testLon:")
        stops.forEach { println("  ID: ${it.id}  onestop: ${it.onestopId}  Name: ${it.stopName}") }

        assertTrue(stops.isNotEmpty(), "Expected at least one stop near Mission Bay, SF")
        assertTrue(stops.all { it.id > 0 }, "All stops must have a valid non-zero ID")
        assertTrue(stops.all { it.stopName.isNotBlank() }, "All stops must have a name")
    }

    @Test
    fun `departures batch API returns departures for Caltrain 4th and King through docker`() {
        val client = TransitlandClient(apiKey(), baseUrl)
        val result = client.getDeparturesBatch(listOf(caltrainOnestopId))
        val departures = result[caltrainOnestopId]?.departures ?: emptyList()

        println("\n[Docker] Departures for Caltrain 4th & King ($caltrainOnestopId):")
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
    fun `departures batch API handles platform stop through docker`() {
        // Platform stops return "children": null from Transitland — verify the proxy handles this cleanly.
        val client = TransitlandClient(apiKey(), baseUrl)
        val result = client.getDeparturesBatch(listOf(caltrainSouthboundOnestopId))
        val departures = result[caltrainSouthboundOnestopId]?.departures ?: emptyList()

        println("\n[Docker] Departures for Caltrain Southbound platform ($caltrainSouthboundOnestopId):")
        departures.forEach { d ->
            println("  ${d.displayDepartureMinutes} min | Route ${d.routeShortName} → ${d.headsign}")
        }

        assertTrue(departures.isNotEmpty(), "Expected departures for the Caltrain Southbound platform")
        assertTrue(departures.all { it.displayDepartureMinutes >= 0 }, "No past departures should be returned")
    }
}
