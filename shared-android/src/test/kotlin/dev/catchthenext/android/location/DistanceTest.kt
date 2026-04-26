package dev.catchthenext.android.location

import dev.catchthenext.model.Stop
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
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

    @Test
    fun `withinMeters returns stops inside threshold sorted by distance`() {
        val near = Stop(1L, "A", "Near", 37.770, -122.410)
        val mid = Stop(2L, "B", "Mid", 37.780, -122.410)   // ~1.1 km away
        val far = Stop(3L, "C", "Far", 37.900, -122.410)   // ~14 km away
        val stops = listOf(far, mid, near)

        val result = stops.withinMeters(37.770, -122.410, 2000)
        assertEquals(2, result.size, "Only near and mid should be within 2000m")
        assertEquals(1L, result[0].first.id, "Nearest first")
        assertEquals(2L, result[1].first.id)
    }

    @Test
    fun `withinMeters returns empty list when nothing in range`() {
        val far = Stop(1L, "A", "Far", 37.900, -122.410)
        assertTrue(listOf(far).withinMeters(37.770, -122.410, 100).isEmpty())
    }

    @Test
    fun `withinMeters includes stops exactly at threshold boundary`() {
        val near = Stop(1L, "A", "Near", 37.770, -122.410)  // 0m
        val result = listOf(near).withinMeters(37.770, -122.410, 0)
        assertEquals(1, result.size, "Stop at 0m should be within 0m threshold")
    }

    @Test
    fun `withinMeters on empty list returns empty list`() {
        assertTrue(emptyList<Stop>().withinMeters(37.77, -122.41, 1000).isEmpty())
    }
}
