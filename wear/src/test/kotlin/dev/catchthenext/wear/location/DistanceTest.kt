package dev.catchthenext.wear.location

import dev.catchthenext.model.Stop
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import kotlin.math.abs

class DistanceTest {

    @Test
    fun `haversineMeters returns zero for identical coordinates`() {
        assertEquals(0.0, haversineMeters(0.0, 0.0, 0.0, 0.0), 0.001)
    }

    @Test
    fun `haversineMeters SF to Oakland within 1 percent of known distance`() {
        // SF City Hall to Oakland City Hall: ~13.3 km
        val dist = haversineMeters(37.7793, -122.4193, 37.8044, -122.2712)
        val knownMeters = 13_300.0
        val tolerance = knownMeters * 0.01
        assert(abs(dist - knownMeters) < tolerance) {
            "Expected ~${knownMeters}m but got ${dist}m"
        }
    }

    @Test
    fun `closestTo returns nearest stop from list`() {
        val near = Stop(1L, "A", "Near", 37.770, -122.410)
        val mid = Stop(2L, "B", "Mid", 37.780, -122.410)
        val far = Stop(3L, "C", "Far", 37.800, -122.410)
        val stops = listOf(far, mid, near)

        val result = stops.closestTo(37.770, -122.410)
        assertEquals(1L, result?.id)
    }

    @Test
    fun `closestTo returns null for empty list`() {
        assertNull(emptyList<Stop>().closestTo(37.77, -122.41))
    }

    @Test
    fun `closestTo with single stop returns that stop`() {
        val only = Stop(5L, "X", "Only", 37.77, -122.41)
        assertEquals(5L, listOf(only).closestTo(0.0, 0.0)?.id)
    }
}
