package dev.catchthenext.android.tile

import dev.catchthenext.model.Departure
import dev.catchthenext.model.Stop
import dev.catchthenext.android.location.LatLon
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TileStateTest {

    private val sfLocation = LatLon(37.770, -122.410)

    private fun stop(id: Long, lat: Double, lon: Double) =
        Stop(id, "S$id", "Stop $id", lat, lon)

    private fun cachedDep(minutesFromNow: Long) = CachedDeparture(
        routeShortName = "14",
        headsign = "Ferry Plaza",
        scheduledEpochMillis = System.currentTimeMillis() + minutesFromNow * 60_000
    )

    private fun fetchReturning(vararg minutes: Long): suspend (Long) -> Pair<List<CachedDeparture>, Long> = {
        val now = System.currentTimeMillis()
        Pair(minutes.map { cachedDep(it) }, now)
    }

    @Test
    fun `no permission returns NoPermission`() = runTest {
        val state = computeTileState(
            favorites = listOf(stop(1L, 37.77, -122.41)),
            location = sfLocation,
            hasPermission = false,
            fetchDepartures = fetchReturning(),
        )
        assertEquals(TileState.NoPermission, state)
    }

    @Test
    fun `empty favorites returns NoFavorites`() = runTest {
        val state = computeTileState(
            favorites = emptyList(),
            location = sfLocation,
            hasPermission = true,
            fetchDepartures = fetchReturning(),
        )
        assertEquals(TileState.NoFavorites, state)
    }

    @Test
    fun `null location returns NoLocation`() = runTest {
        val state = computeTileState(
            favorites = listOf(stop(1L, 37.77, -122.41)),
            location = null,
            hasPermission = true,
            fetchDepartures = fetchReturning(),
        )
        assertEquals(TileState.NoLocation, state)
    }

    @Test
    fun `in-range stops are returned sorted by distance`() = runTest {
        val near = stop(1L, 37.770, -122.410)
        val mid = stop(2L, 37.780, -122.410)
        val far = stop(3L, 37.800, -122.410)

        val state = computeTileState(
            favorites = listOf(far, mid, near),
            location = sfLocation,
            hasPermission = true,
            fetchDepartures = fetchReturning(5L, 12L),
        )

        assertTrue(state is TileState.Ready, "Expected Ready but got $state")
        val ready = state as TileState.Ready
        assertEquals(1L, ready.stops[0].stop.id, "Nearest stop should be first")
        assertEquals(2, ready.stops[0].departures.size)
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
            fetchDepartures = fetchReturning(),
        )
        assertEquals(TileState.NoPermission, state)
    }

    @Test
    fun `past departures are filtered out`() = runTest {
        val state = computeTileState(
            favorites = listOf(stop(1L, 37.77, -122.41)),
            location = sfLocation,
            hasPermission = true,
            fetchDepartures = fetchReturning(-5L, 3L, 10L),
        )
        val ready = state as TileState.Ready
        assertEquals(2, ready.stops[0].departures.size)
    }

    @Test
    fun `stops beyond threshold are excluded`() = runTest {
        val near = stop(1L, 37.770, -122.410)
        val veryFar = stop(2L, 37.900, -122.410)

        val state = computeTileState(
            favorites = listOf(veryFar, near),
            location = sfLocation,
            hasPermission = true,
            thresholdMeters = 1609,
            fetchDepartures = fetchReturning(5L),
        )
        val ready = state as TileState.Ready
        assertEquals(1, ready.stops.size, "Only the near stop should be included")
        assertEquals(1L, ready.stops[0].stop.id)
    }

    @Test
    fun `falls back to closest stop when none within threshold`() = runTest {
        val veryFar = stop(1L, 37.900, -122.410)

        val state = computeTileState(
            favorites = listOf(veryFar),
            location = sfLocation,
            hasPermission = true,
            thresholdMeters = 100,
            fetchDepartures = fetchReturning(5L),
        )
        val ready = state as TileState.Ready
        assertEquals(1, ready.stops.size, "Should fall back to closest even if outside threshold")
        assertEquals(1L, ready.stops[0].stop.id)
    }

    @Test
    fun `partial fetch failure shows successful stops`() = runTest {
        val stop1 = stop(1L, 37.770, -122.410)
        val stop2 = stop(2L, 37.771, -122.410)

        val state = computeTileState(
            favorites = listOf(stop1, stop2),
            location = sfLocation,
            hasPermission = true,
            thresholdMeters = 1609,
            fetchDepartures = { stopId ->
                if (stopId == stop2.id) throw RuntimeException("stop2 failed")
                Pair(listOf(cachedDep(5L)), System.currentTimeMillis())
            },
        )
        assertTrue(state is TileState.Ready, "Expected Ready with partial results")
        val ready = state as TileState.Ready
        assertEquals(1, ready.stops.size, "Only successful stop should appear")
        assertEquals(1L, ready.stops[0].stop.id)
    }

    private fun swd(stop: Stop, vararg deps: CachedDeparture) =
        StopWithDepartures(stop, 0.0, deps.toList())

    @Test
    fun `groupDepartures groups same route into one entry`() {
        val now = System.currentTimeMillis()
        val s = stop(1L, 37.770, -122.410)
        val groups = groupDepartures(listOf(swd(s,
            CachedDeparture("14", "Ferry Plaza", now + 5 * 60_000),
            CachedDeparture("14", "Ferry Plaza", now + 15 * 60_000),
        )))
        assertEquals(1, groups.size)
        assertEquals(listOf(5L, 15L), groups[0].minutesList)
    }

    @Test
    fun `groupDepartures caps minutesList at maxPerGroup`() {
        val now = System.currentTimeMillis()
        val s = stop(1L, 37.770, -122.410)
        val groups = groupDepartures(listOf(swd(s,
            CachedDeparture("14", "Ferry Plaza", now + 5 * 60_000),
            CachedDeparture("14", "Ferry Plaza", now + 15 * 60_000),
            CachedDeparture("14", "Ferry Plaza", now + 25 * 60_000),
            CachedDeparture("14", "Ferry Plaza", now + 35 * 60_000),
        )), maxPerGroup = 3)
        assertEquals(1, groups.size)
        assertEquals(3, groups[0].minutesList.size)
        assertEquals(listOf(5L, 15L, 25L), groups[0].minutesList)
    }

    @Test
    fun `groupDepartures separates different routes`() {
        val now = System.currentTimeMillis()
        val s = stop(1L, 37.770, -122.410)
        val groups = groupDepartures(listOf(swd(s,
            CachedDeparture("14", "Ferry Plaza", now + 5 * 60_000),
            CachedDeparture("49", "Caltrain", now + 10 * 60_000),
        )))
        assertEquals(2, groups.size)
        assertEquals("14", groups[0].routeShortName)
        assertEquals("49", groups[1].routeShortName)
    }

    @Test
    fun `groupDepartures sorts groups by first departure time`() {
        val now = System.currentTimeMillis()
        val s = stop(1L, 37.770, -122.410)
        val groups = groupDepartures(listOf(swd(s,
            CachedDeparture("49", "Caltrain", now + 10 * 60_000),
            CachedDeparture("14", "Ferry Plaza", now + 3 * 60_000),
        )))
        assertEquals("14", groups[0].routeShortName, "Route 14 departs sooner, should be first")
    }

    @Test
    fun `groupDepartures sets showStopTag true for multiple stops`() {
        val now = System.currentTimeMillis()
        val s1 = stop(1L, 37.770, -122.410)
        val s2 = stop(2L, 37.771, -122.410)
        val groups = groupDepartures(listOf(
            swd(s1, CachedDeparture("14", "Ferry Plaza", now + 5 * 60_000)),
            swd(s2, CachedDeparture("22", "Mission", now + 8 * 60_000)),
        ))
        assertTrue(groups.all { it.showStopTag })
    }

    @Test
    fun `groupDepartures respects custom filter`() {
        val now = System.currentTimeMillis()
        val s = stop(1L, 37.770, -122.410)
        val groups = groupDepartures(
            stops = listOf(swd(s,
                CachedDeparture("14", "Ferry Plaza", now + 5 * 60_000),
                CachedDeparture("14", "Ferry Plaza", now + 70 * 60_000),
            )),
            filter = { it.currentMinutes() in 0..59 },
        )
        assertEquals(1, groups.size)
        assertEquals(1, groups[0].minutesList.size, "Departure beyond 59 min should be filtered out")
    }

    private fun cachedStopDeps(stopId: Long, fetchedAt: Long, vararg minutes: Long) =
        CachedStopDepartures(
            stopId = stopId,
            departures = minutes.map { cachedDep(it) },
            fetchedAt = fetchedAt,
        )

    @Test
    fun `makeFetchNetworkDepartures returns cache when fresh`() = runTest {
        val now = System.currentTimeMillis()
        val freshStop = cachedStopDeps(1L, now - 30_000, 5L, 10L)
        val cache = CachedTileData(lat = null, lon = null, nearbyDepartures = listOf(freshStop))

        var networkCalled = false
        val fetch = makeFetchNetworkDepartures(
            getDepartures = { networkCalled = true; emptyList() },
            cache = cache,
            forceFresh = false,
        )

        val (deps, fetchedAt) = fetch(1L)
        assertFalse(networkCalled, "Network should not be called for fresh cache")
        assertEquals(2, deps.size)
        assertEquals(freshStop.fetchedAt, fetchedAt)
    }

    @Test
    fun `makeFetchNetworkDepartures hits network when cache is stale`() = runTest {
        val now = System.currentTimeMillis()
        val staleStop = cachedStopDeps(1L, now - 90_000, 5L)
        val cache = CachedTileData(lat = null, lon = null, nearbyDepartures = listOf(staleStop))

        var networkCalled = false
        val fetch = makeFetchNetworkDepartures(
            getDepartures = { stopId ->
                networkCalled = true
                listOf(Departure(stopId, "10:00", 10L, "14", "Mission 14", "Ferry Plaza"))
            },
            cache = cache,
            forceFresh = false,
        )

        val (deps, _) = fetch(1L)
        assertTrue(networkCalled, "Network should be called for stale cache")
        assertEquals(1, deps.size)
    }

    @Test
    fun `makeFetchNetworkDepartures bypasses fresh cache when forceFresh`() = runTest {
        val now = System.currentTimeMillis()
        val freshStop = cachedStopDeps(1L, now - 10_000, 5L)
        val cache = CachedTileData(lat = null, lon = null, nearbyDepartures = listOf(freshStop))

        var networkCalled = false
        val fetch = makeFetchNetworkDepartures(
            getDepartures = { stopId ->
                networkCalled = true
                listOf(Departure(stopId, "10:00", 5L, "14", "Mission 14", "Ferry Plaza"))
            },
            cache = cache,
            forceFresh = true,
        )

        fetch(1L)
        assertTrue(networkCalled, "Network should be called when forceFresh=true even with fresh cache")
    }

    @Test
    fun `mixed cache - only stale stop fetches from network`() = runTest {
        val now = System.currentTimeMillis()
        val stop1 = stop(1L, 37.770, -122.410)
        val stop2 = stop(2L, 37.771, -122.410)

        val freshCached = cachedStopDeps(1L, now - 30_000, 10L)
        val staleCached = cachedStopDeps(2L, now - 90_000, 15L)
        val cache = CachedTileData(lat = null, lon = null, nearbyDepartures = listOf(freshCached, staleCached))

        val fetchCallLog = mutableListOf<Long>()
        val state = computeTileState(
            favorites = listOf(stop1, stop2),
            location = sfLocation,
            hasPermission = true,
            thresholdMeters = 1609,
            fetchDepartures = makeFetchNetworkDepartures(
                getDepartures = { stopId ->
                    fetchCallLog.add(stopId)
                    listOf(Departure(stopId, "10:00", 20L, "14", "Mission 14", "Ferry Plaza"))
                },
                cache = cache,
            ),
        )

        assertTrue(state is TileState.Ready)
        assertEquals(listOf(2L), fetchCallLog, "Only stale stop 2 should have triggered a network call")
    }
}
