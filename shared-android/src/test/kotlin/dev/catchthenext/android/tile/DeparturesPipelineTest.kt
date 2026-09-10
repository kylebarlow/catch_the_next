package dev.catchthenext.android.tile

import dev.catchthenext.api.StopDepartures
import dev.catchthenext.model.Departure
import dev.catchthenext.model.DepartureTimeSource
import dev.catchthenext.model.Stop
import dev.catchthenext.android.location.LatLon
import kotlinx.coroutines.delay
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
        quickLocation: suspend () -> LatLon? = { null },
        updateCachedLocation: suspend (Double, Double) -> Unit = { _, _ -> },
    ): DeparturesPipeline {
        var favs = favorites
        return DeparturesPipeline(
            getDeparturesBatch = getDeparturesBatch,
            getNearbyStops = getNearbyStops,
            getFavorites = { favs },
            saveFavorites = { stops -> favs = stops; saveFavorites(stops) },
            readCache = { cache },
            persistDepartures = persistDepartures,
            updateCachedLocation = updateCachedLocation,
            hasLocationPermission = { hasPermission },
            currentLocation = { location },
            thresholdMeters = { 1609 },
            quickLocation = quickLocation,
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
    fun `quickCacheRead returns a stale-but-recent entry flagged isStale`() = runTest {
        val cache = cacheWith(fetchedAt = System.currentTimeMillis() - 90_000, departureInMs = 300_000)
        val state = pipeline(favorites = listOf(stop(1L)), cache = cache).quickCacheRead()
        assertTrue(state is TileState.Ready)
        val stops = (state as TileState.Ready).stops
        assertEquals(1, stops.size)
        assertTrue(stops[0].isStale, "An entry older than CACHE_TTL_MS is flagged stale")
    }

    @Test
    fun `quickCacheRead returns null past the quick cache window`() = runTest {
        val cache = cacheWith(
            fetchedAt = System.currentTimeMillis() - Tuning.QUICK_CACHE_MAX_AGE_MS - 1,
            departureInMs = 300_000,
        )
        assertNull(pipeline(favorites = listOf(stop(1L)), cache = cache).quickCacheRead())
    }

    @Test
    fun `quickCacheRead drops departures already in the past`() = runTest {
        val cache = CachedTileData(
            lat = sf.lat, lon = sf.lon,
            nearbyDepartures = listOf(
                CachedStopDepartures(
                    stopId = 1L,
                    departures = listOf(
                        CachedDeparture("14", "Ferry", System.currentTimeMillis() - 300_000, DepartureTimeSource.SCHEDULED),
                        CachedDeparture("14", "Ferry", System.currentTimeMillis() + 300_000, DepartureTimeSource.SCHEDULED),
                    ),
                    fetchedAt = System.currentTimeMillis() - 90_000,
                )
            )
        )
        val state = pipeline(favorites = listOf(stop(1L)), cache = cache).quickCacheRead() as TileState.Ready
        assertEquals(1, state.stops[0].departures.size, "Only the future departure survives")
    }

    @Test
    fun `quickCacheRead returns null when every cached departure has passed`() = runTest {
        val cache = cacheWith(fetchedAt = System.currentTimeMillis() - 90_000, departureInMs = -300_000)
        assertNull(pipeline(favorites = listOf(stop(1L)), cache = cache).quickCacheRead())
    }

    private fun cacheWith(fetchedAt: Long, departureInMs: Long) = CachedTileData(
        lat = sf.lat, lon = sf.lon,
        nearbyDepartures = listOf(
            CachedStopDepartures(
                stopId = 1L,
                departures = listOf(
                    CachedDeparture("14", "Ferry", System.currentTimeMillis() + departureInMs, DepartureTimeSource.SCHEDULED)
                ),
                fetchedAt = fetchedAt,
            )
        )
    )

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

    // --- Location runs in parallel with the fetch (package E) ---

    /** A stop ~2.5 km north of [sf] — outside the 1609 m threshold from sf, inside it from itself. */
    private val north = LatLon(37.792, -122.410)

    @Test
    fun `the batch fetch is issued before a slow fresh fix completes`() = runTest {
        val events = mutableListOf<String>()
        val p = DeparturesPipeline(
            getDeparturesBatch = { ids -> events.add("batch"); ids.associateWith { departures(0L) } },
            getNearbyStops = { _, _ -> emptyList() },
            getFavorites = { listOf(stop(1L)) },
            saveFavorites = {},
            readCache = { CachedTileData(lat = sf.lat, lon = sf.lon) },
            persistDepartures = {},
            updateCachedLocation = { _, _ -> },
            hasLocationPermission = { true },
            currentLocation = { delay(5_000); events.add("fresh"); sf },
            thresholdMeters = { 1609 },
            quickLocation = { sf },
        )
        assertTrue(p.computeState(forceFresh = false) is TileState.Ready)
        assertEquals(listOf("batch", "fresh"), events, "The fetch goes out before the fresh fix lands")
    }

    @Test
    fun `a fresh fix selecting the same stops does not refetch`() = runTest {
        var batches = 0
        val p = pipeline(
            getDeparturesBatch = { ids -> batches++; ids.associateWith { departures(0L) } },
            quickLocation = { sf },
            location = LatLon(sf.lat + 0.0001, sf.lon),
        )
        assertTrue(p.computeState(forceFresh = false) is TileState.Ready)
        assertEquals(1, batches, "Same stop selection means one batch call")
    }

    @Test
    fun `a fresh fix selecting different stops refetches for the fresh location`() = runTest {
        val batches = mutableListOf<List<String>>()
        val far = Stop(2L, "S2", "Stop 2", north.lat, north.lon, onestopId = "s-2")
        val p = pipeline(
            favorites = listOf(stop(1L), far),
            getDeparturesBatch = { ids -> batches.add(ids); ids.associateWith { departures(0L) } },
            quickLocation = { sf },
            location = north,
        )
        val state = p.computeState(forceFresh = false)
        assertTrue(state is TileState.Ready)
        assertEquals(2, batches.size, "The stop selection changed, so the fetch is redone")
        assertEquals(listOf("s-2"), batches[1], "The second fetch is for the fresh location's stop")
        assertEquals(2L, (state as TileState.Ready).stops[0].stop.id)
    }

    @Test
    fun `a null fresh fix keeps the quick-location state and the cached location`() = runTest {
        var batches = 0
        var cachedWrites = 0
        val p = pipeline(
            getDeparturesBatch = { ids -> batches++; ids.associateWith { departures(0L) } },
            quickLocation = { sf },
            location = null,
            updateCachedLocation = { _, _ -> cachedWrites++ },
        )
        assertTrue(p.computeState(forceFresh = false) is TileState.Ready)
        assertEquals(1, batches)
        assertEquals(0, cachedWrites, "A null fresh fix must not overwrite the cached location")
    }

    @Test
    fun `with no quick location and no cache the fresh fix is awaited`() = runTest {
        var batches = 0
        val p = pipeline(
            getDeparturesBatch = { ids -> batches++; ids.associateWith { departures(0L) } },
            cache = CachedTileData(lat = null, lon = null),
            quickLocation = { null },
            location = sf,
        )
        assertTrue(p.computeState(forceFresh = false) is TileState.Ready)
        assertEquals(1, batches)
    }

    @Test
    fun `no location at all returns NoLocation`() = runTest {
        val p = pipeline(cache = CachedTileData(lat = null, lon = null), quickLocation = { null }, location = null)
        assertEquals(TileState.NoLocation, p.computeState(forceFresh = false))
    }
}
