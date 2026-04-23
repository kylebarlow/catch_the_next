package dev.catchthenext.wear.tile

import dev.catchthenext.model.Departure
import dev.catchthenext.model.Stop
import dev.catchthenext.wear.location.LatLon
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TileStateTest {

    private val sfLocation = LatLon(37.770, -122.410)

    private fun stop(id: Long, lat: Double, lon: Double) =
        Stop(id, "S$id", "Stop $id", lat, lon)

    private fun departure(stopId: Long, minutes: Long) =
        Departure(stopId, "12:00", minutes, "14", "Mission", "Ferry Plaza")

    @Test
    fun `no permission returns NoPermission`() = runTest {
        val state = computeTileState(
            favorites = listOf(stop(1L, 37.77, -122.41)),
            location = sfLocation,
            hasPermission = false,
            fetchDepartures = { emptyList() },
        )
        assertEquals(TileState.NoPermission, state)
    }

    @Test
    fun `empty favorites returns NoFavorites`() = runTest {
        val state = computeTileState(
            favorites = emptyList(),
            location = sfLocation,
            hasPermission = true,
            fetchDepartures = { emptyList() },
        )
        assertEquals(TileState.NoFavorites, state)
    }

    @Test
    fun `null location returns NoLocation`() = runTest {
        val state = computeTileState(
            favorites = listOf(stop(1L, 37.77, -122.41)),
            location = null,
            hasPermission = true,
            fetchDepartures = { emptyList() },
        )
        assertEquals(TileState.NoLocation, state)
    }

    @Test
    fun `three favorites returns Ready with closest stop`() = runTest {
        val near = stop(1L, 37.770, -122.410)  // same as location
        val mid = stop(2L, 37.780, -122.410)   // ~1.1 km away
        val far = stop(3L, 37.800, -122.410)   // ~3.3 km away
        val deps = listOf(departure(1L, 5L), departure(1L, 12L))

        val state = computeTileState(
            favorites = listOf(far, mid, near),
            location = sfLocation,
            hasPermission = true,
            fetchDepartures = { deps },
        )

        assertTrue(state is TileState.Ready, "Expected Ready but got $state")
        val ready = state as TileState.Ready
        assertEquals(1L, ready.stop.id)
        assertEquals(2, ready.departures.size)
    }

    @Test
    fun `fetchDepartures throwing returns NetworkError`() = runTest {
        val state = computeTileState(
            favorites = listOf(stop(1L, 37.77, -122.41)),
            location = sfLocation,
            hasPermission = true,
            fetchDepartures = { throw RuntimeException("timeout") },
        )
        assertTrue(state is TileState.NetworkError)
        assertEquals("timeout", (state as TileState.NetworkError).message)
    }

    @Test
    fun `no permission check happens before empty favorites check`() = runTest {
        val state = computeTileState(
            favorites = emptyList(),
            location = null,
            hasPermission = false,
            fetchDepartures = { emptyList() },
        )
        assertEquals(TileState.NoPermission, state)
    }
}
