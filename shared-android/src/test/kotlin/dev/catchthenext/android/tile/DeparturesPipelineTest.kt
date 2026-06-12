package dev.catchthenext.android.tile

import dev.catchthenext.api.StopDepartures
import dev.catchthenext.model.Departure
import dev.catchthenext.model.DepartureTimeSource
import dev.catchthenext.model.Stop
import dev.catchthenext.android.location.LatLon
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DeparturesPipelineTest {

    private val sf = LatLon(37.770, -122.410)

    private fun stop(id: Long, onestop: String = "s-$id") =
        Stop(id, "S$id", "Stop $id", 37.770, -122.410, onestopId = onestop)

    private fun departures(stopId: Long) =
        StopDepartures(stopId, listOf(
            Departure(stopId, "10:00", 5L, null, null, "10:00", 5L, DepartureTimeSource.SCHEDULED, "14", "Mission 14", "Ferry Plaza")
        ))

    /** Builds a pipeline with sensible defaults; override the collaborators a test cares about. */
    private fun pipeline(
        favorites: List<Stop> = listOf(stop(1L)),
        getDeparturesBatch: suspend (List<String>) -> Map<String, StopDepartures> = { ids ->
            ids.associateWith { departures(0L) }
        },
        getNearbyStops: suspend (Double, Double) -> List<Stop> = { _, _ -> emptyList() },
        saveFavorites: suspend (List<Stop>) -> Unit = {},
        cache: CachedTileData = CachedTileData(lat = sf.lat, lon = sf.lon),
        persistDepartures: suspend (List<StopWithDepartures>) -> Unit = {},
        hasPermission: Boolean = true,
        location: LatLon? = sf,
    ): DeparturesPipeline {
        var favs = favorites
        return DeparturesPipeline(
            getDeparturesBatch = getDeparturesBatch,
            getNearbyStops = getNearbyStops,
            getFavorites = { favs },
            saveFavorites = { stops -> favs = stops; saveFavorites(stops) },
            readCache = { cache },
            persistDepartures = persistDepartures,
            updateCachedLocation = { _, _ -> },
            hasLocationPermission = { hasPermission },
            currentLocation = { location },
            thresholdMeters = { 1609 },
        )
    }

    @Test
    fun `no stale stops means one fetch and no save`() = runTest {
        var saveCalled = false
        val batchCalls = mutableListOf<List<String>>()
        val p = pipeline(
            favorites = listOf(stop(1L)),
            getDeparturesBatch = { ids -> batchCalls.add(ids); ids.associateWith { departures(0L) } },
            saveFavorites = { saveCalled = true },
        )

        val state = p.computeState(forceFresh = false)
        assertTrue(state is TileState.Ready)
        assertFalse(saveCalled, "No save without a stale stop")
        assertEquals(1, batchCalls.size, "Exactly one fetch when nothing is stale")
    }

    @Test
    fun `no permission returns NoPermission without touching location or network`() = runTest {
        var networkCalled = false
        var locationCalled = false
        val p = DeparturesPipeline(
            getDeparturesBatch = { networkCalled = true; emptyMap() },
            getNearbyStops = { _, _ -> emptyList() },
            getFavorites = { listOf(stop(1L)) },
            saveFavorites = {},
            readCache = { CachedTileData(lat = sf.lat, lon = sf.lon) },
            persistDepartures = {},
            updateCachedLocation = { _, _ -> },
            hasLocationPermission = { false },
            currentLocation = { locationCalled = true; sf },
            thresholdMeters = { 1609 },
        )

        val state = p.computeState(forceFresh = false)
        assertEquals(TileState.NoPermission, state)
        assertFalse(networkCalled, "Network must not be hit without permission")
        assertFalse(locationCalled, "Location must not be read without permission")
    }

    @Test
    fun `quickCacheRead returns Ready for a fresh cache entry`() = runTest {
        val cache = CachedTileData(
            lat = sf.lat, lon = sf.lon,
            nearbyDepartures = listOf(
                CachedStopDepartures(
                    stopId = 1L,
                    departures = listOf(CachedDeparture("14", "Ferry", System.currentTimeMillis() + 300_000, DepartureTimeSource.SCHEDULED)),
                    fetchedAt = System.currentTimeMillis(),
                )
            )
        )
        val state = pipeline(favorites = listOf(stop(1L)), cache = cache).quickCacheRead()
        assertTrue(state is TileState.Ready)
        assertEquals(1, (state as TileState.Ready).stops.size)
    }

    @Test
    fun `quickCacheRead returns null when only stale entries cached`() = runTest {
        val cache = CachedTileData(
            lat = sf.lat, lon = sf.lon,
            nearbyDepartures = listOf(
                CachedStopDepartures(
                    stopId = 1L,
                    departures = listOf(CachedDeparture("14", "Ferry", System.currentTimeMillis() + 300_000, DepartureTimeSource.SCHEDULED)),
                    fetchedAt = System.currentTimeMillis() - 90_000,
                )
            )
        )
        assertNull(pipeline(favorites = listOf(stop(1L)), cache = cache).quickCacheRead())
    }

    @Test
    fun `quickCacheRead returns NoFavorites when there are no favorites`() = runTest {
        val state = pipeline(favorites = emptyList()).quickCacheRead()
        assertEquals(TileState.NoFavorites, state)
    }

    @Test
    fun `quickCacheRead returns NoPermission without permission`() = runTest {
        val state = pipeline(hasPermission = false).quickCacheRead()
        assertEquals(TileState.NoPermission, state)
    }
}
