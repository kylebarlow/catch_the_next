package dev.catchthenext.android.tile

import dev.catchthenext.api.StopDepartures
import dev.catchthenext.model.Departure
import dev.catchthenext.model.DepartureTimeSource
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
        Stop(id, "S$id", "Stop $id", lat, lon, onestopId = "s-$id")

    // currentMinutes() floors (departure - now) / 60_000 against a clock read *after* these
    // departures are built, so a departure placed exactly on a minute boundary floors to one
    // minute less once a few millis elapse. Bake in a half-minute buffer so the integer-minute
    // result is stable regardless of how long the test takes to run.
    private val minuteBufferMillis = 30_000L

    private fun cachedDep(minutesFromNow: Long, timeSource: DepartureTimeSource = DepartureTimeSource.SCHEDULED) =
        depAt("14", "Ferry Plaza", minutesFromNow, timeSource)

    private fun depAt(
        route: String,
        headsign: String,
        minutesFromNow: Long,
        timeSource: DepartureTimeSource = DepartureTimeSource.SCHEDULED,
    ) = CachedDeparture(
        routeShortName = route,
        headsign = headsign,
        departureEpochMillis = System.currentTimeMillis() + minutesFromNow * 60_000 + minuteBufferMillis,
        timeSource = timeSource,
    )

    private fun fetchReturning(vararg minutes: Long): suspend (List<Long>) -> Map<Long, CachedStopFetch> = { stopIds ->
        val now = System.currentTimeMillis()
        stopIds.associateWith { CachedStopFetch(minutes.map { cachedDep(it) }, emptyList(), now) }
    }

    @Test
    fun `no permission returns NoPermission`() = runTest {
        val state = computeTileState(
            favorites = listOf(stop(1L, 37.77, -122.41)),
            location = sfLocation,
            hasPermission = false,
            fetchDeparturesBatch = fetchReturning(),
        )
        assertEquals(TileState.NoPermission, state)
    }

    @Test
    fun `empty favorites returns NoFavorites`() = runTest {
        val state = computeTileState(
            favorites = emptyList(),
            location = sfLocation,
            hasPermission = true,
            fetchDeparturesBatch = fetchReturning(),
        )
        assertEquals(TileState.NoFavorites, state)
    }

    @Test
    fun `null location returns NoLocation`() = runTest {
        val state = computeTileState(
            favorites = listOf(stop(1L, 37.77, -122.41)),
            location = null,
            hasPermission = true,
            fetchDeparturesBatch = fetchReturning(),
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
            fetchDeparturesBatch = { stopIds ->
                val now = System.currentTimeMillis()
                stopIds.associateWith { CachedStopFetch(listOf(cachedDep(5L), cachedDep(12L)), emptyList(), now) }
            },
        )

        assertTrue(state is TileState.Ready, "Expected Ready but got $state")
        val ready = state as TileState.Ready
        assertEquals(1L, ready.stops[0].stop.id, "Nearest stop should be first")
        assertEquals(2, ready.stops[0].departures.size)
    }

    @Test
    fun `fetchDeparturesBatch throwing returns NetworkError`() = runTest {
        val state = computeTileState(
            favorites = listOf(stop(1L, 37.77, -122.41)),
            location = sfLocation,
            hasPermission = true,
            fetchDeparturesBatch = { throw RuntimeException("timeout") },
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
            fetchDeparturesBatch = fetchReturning(),
        )
        assertEquals(TileState.NoPermission, state)
    }

    @Test
    fun `past departures are filtered out`() = runTest {
        val state = computeTileState(
            favorites = listOf(stop(1L, 37.77, -122.41)),
            location = sfLocation,
            hasPermission = true,
            fetchDeparturesBatch = fetchReturning(-5L, 3L, 10L),
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
            fetchDeparturesBatch = fetchReturning(5L),
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
            fetchDeparturesBatch = fetchReturning(5L),
        )
        val ready = state as TileState.Ready
        assertEquals(1, ready.stops.size, "Should fall back to closest even if outside threshold")
        assertEquals(1L, ready.stops[0].stop.id)
    }

    @Test
    fun `batch fetch returns all stops on success`() = runTest {
        val stop1 = stop(1L, 37.770, -122.410)
        val stop2 = stop(2L, 37.771, -122.410)

        val state = computeTileState(
            favorites = listOf(stop1, stop2),
            location = sfLocation,
            hasPermission = true,
            thresholdMeters = 1609,
            fetchDeparturesBatch = { stopIds ->
                val now = System.currentTimeMillis()
                stopIds.associateWith { CachedStopFetch(listOf(cachedDep(5L)), emptyList(), now) }
            },
        )
        assertTrue(state is TileState.Ready)
        val ready = state as TileState.Ready
        assertEquals(2, ready.stops.size)
    }

    private fun swd(stop: Stop, vararg deps: CachedDeparture) =
        StopWithDepartures(stop, 0.0, deps.toList())

    @Test
    fun `groupDepartures groups same route into one entry`() {
        val s = stop(1L, 37.770, -122.410)
        val groups = groupDepartures(listOf(swd(s,
            depAt("14", "Ferry Plaza", 5),
            depAt("14", "Ferry Plaza", 15),
        )))
        assertEquals(1, groups.size)
        assertEquals(listOf(5L, 15L), groups[0].times.map { it.minutes })
    }

    @Test
    fun `groupDepartures caps times at maxPerGroup`() {
        val s = stop(1L, 37.770, -122.410)
        val groups = groupDepartures(listOf(swd(s,
            depAt("14", "Ferry Plaza", 5),
            depAt("14", "Ferry Plaza", 15),
            depAt("14", "Ferry Plaza", 25),
            depAt("14", "Ferry Plaza", 35),
        )), maxPerGroup = 3)
        assertEquals(1, groups.size)
        assertEquals(3, groups[0].times.size)
        assertEquals(listOf(5L, 15L, 25L), groups[0].times.map { it.minutes })
    }

    @Test
    fun `groupDepartures separates different routes`() {
        val s = stop(1L, 37.770, -122.410)
        val groups = groupDepartures(listOf(swd(s,
            depAt("14", "Ferry Plaza", 5),
            depAt("49", "Caltrain", 10),
        )))
        assertEquals(2, groups.size)
        assertEquals("14", groups[0].routeShortName)
        assertEquals("49", groups[1].routeShortName)
    }

    @Test
    fun `groupDepartures sorts groups by first departure time`() {
        val s = stop(1L, 37.770, -122.410)
        val groups = groupDepartures(listOf(swd(s,
            depAt("49", "Caltrain", 10),
            depAt("14", "Ferry Plaza", 3),
        )))
        assertEquals("14", groups[0].routeShortName, "Route 14 departs sooner, should be first")
    }

    @Test
    fun `groupDepartures sets showStopTag true for multiple stops`() {
        val s1 = stop(1L, 37.770, -122.410)
        val s2 = stop(2L, 37.771, -122.410)
        val groups = groupDepartures(listOf(
            swd(s1, depAt("14", "Ferry Plaza", 5)),
            swd(s2, depAt("22", "Mission", 8)),
        ))
        assertTrue(groups.all { it.showStopTag })
    }

    @Test
    fun `groupDepartures respects custom filter`() {
        val s = stop(1L, 37.770, -122.410)
        val groups = groupDepartures(
            stops = listOf(swd(s,
                depAt("14", "Ferry Plaza", 5),
                depAt("14", "Ferry Plaza", 70),
            )),
            filter = { it.currentMinutes() in 0..59 },
        )
        assertEquals(1, groups.size)
        assertEquals(1, groups[0].times.size, "Departure beyond 59 min should be filtered out")
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
            getDepartures = { stopId -> networkCalled = true; StopDepartures(stopId, emptyList()) },
            cache = cache,
            forceFresh = false,
        )

        val result = fetch(1L)
        assertFalse(networkCalled, "Network should not be called for fresh cache")
        assertEquals(2, result.departures.size)
        assertEquals(freshStop.fetchedAt, result.fetchedAt)
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
                StopDepartures(stopId, listOf(Departure(stopId, "10:00", 10L, null, null, "10:00", 10L, DepartureTimeSource.SCHEDULED, "14", "Mission 14", "Ferry Plaza")))
            },
            cache = cache,
            forceFresh = false,
        )

        val result = fetch(1L)
        assertTrue(networkCalled, "Network should be called for stale cache")
        assertEquals(1, result.departures.size)
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
                StopDepartures(stopId, listOf(Departure(stopId, "10:00", 5L, null, null, "10:00", 5L, DepartureTimeSource.SCHEDULED, "14", "Mission 14", "Ferry Plaza")))
            },
            cache = cache,
            forceFresh = true,
        )

        fetch(1L)
        assertTrue(networkCalled, "Network should be called when forceFresh=true even with fresh cache")
    }

    @Test
    fun `mixed cache - only stale stop ids sent in batch`() = runTest {
        val now = System.currentTimeMillis()
        val stop1 = stop(1L, 37.770, -122.410)
        val stop2 = stop(2L, 37.771, -122.410)

        val freshCached = cachedStopDeps(1L, now - 30_000, 10L)
        val staleCached = cachedStopDeps(2L, now - 90_000, 15L)
        val cache = CachedTileData(lat = null, lon = null, nearbyDepartures = listOf(freshCached, staleCached))

        val batchCallLog = mutableListOf<List<String>>()
        val state = computeTileState(
            favorites = listOf(stop1, stop2),
            location = sfLocation,
            hasPermission = true,
            thresholdMeters = 1609,
            fetchDeparturesBatch = makeFetchNetworkDeparturesBatch(
                getDeparturesBatch = { stopIds ->
                    batchCallLog.add(stopIds)
                    stopIds.associateWith { _ ->
                        StopDepartures(0L, listOf(Departure(0L, "10:00", 20L, null, null, "10:00", 20L, DepartureTimeSource.SCHEDULED, "14", "Mission 14", "Ferry Plaza")))
                    }
                },
                cache = cache,
                stops = listOf(stop1, stop2),
            ),
        )

        assertTrue(state is TileState.Ready)
        assertEquals(1, batchCallLog.size, "Should make exactly one batch call")
        assertEquals(listOf("s-2"), batchCallLog[0], "Only stale stop 2 should be in the batch request")
    }

    @Test
    fun `realtime departure maps to LIVE timeSource`() {
        val s = stop(1L, 37.770, -122.410)
        val groups = groupDepartures(listOf(swd(s,
            depAt("14", "Ferry Plaza", 5, DepartureTimeSource.LIVE),
        )))
        assertEquals(DepartureTimeSource.LIVE, groups[0].times[0].timeSource)
    }

    @Test
    fun `scheduled-only departure maps to SCHEDULED timeSource`() {
        val s = stop(1L, 37.770, -122.410)
        val groups = groupDepartures(listOf(swd(s,
            depAt("14", "Ferry Plaza", 5, DepartureTimeSource.SCHEDULED),
        )))
        assertEquals(DepartureTimeSource.SCHEDULED, groups[0].times[0].timeSource)
    }

    @Test
    fun `cache retains timeSource through makeFetchNetworkDepartures`() = runTest {
        val now = System.currentTimeMillis()
        val cache = CachedTileData(lat = null, lon = null, nearbyDepartures = emptyList())

        val fetch = makeFetchNetworkDepartures(
            getDepartures = { stopId ->
                StopDepartures(stopId, listOf(
                    Departure(stopId, "10:00", 10L, "10:03", 13L, "10:03", 13L, DepartureTimeSource.LIVE, "14", "", "Ferry Plaza"),
                    Departure(stopId, "10:15", 25L, null, null, "10:15", 25L, DepartureTimeSource.SCHEDULED, "14", "", "Ferry Plaza"),
                ))
            },
            cache = cache,
            forceFresh = true,
        )

        val result = fetch(1L)
        assertEquals(2, result.departures.size)
        assertEquals(DepartureTimeSource.LIVE, result.departures[0].timeSource)
        assertEquals(DepartureTimeSource.SCHEDULED, result.departures[1].timeSource)
    }

    @Test
    fun `grouping preserves source for each displayed time`() {
        val s = stop(1L, 37.770, -122.410)
        val groups = groupDepartures(listOf(swd(s,
            depAt("14", "Ferry Plaza", 5, DepartureTimeSource.LIVE),
            depAt("14", "Ferry Plaza", 15, DepartureTimeSource.SCHEDULED),
        )))
        assertEquals(1, groups.size)
        assertEquals(2, groups[0].times.size)
        assertEquals(DepartureTimeSource.LIVE, groups[0].times[0].timeSource)
        assertEquals(DepartureTimeSource.SCHEDULED, groups[0].times[1].timeSource)
    }

    @Test
    fun `sorting uses effective display minutes`() {
        val s = stop(1L, 37.770, -122.410)
        val groups = groupDepartures(listOf(swd(s,
            depAt("49", "Caltrain", 10, DepartureTimeSource.SCHEDULED),
            depAt("14", "Ferry Plaza", 3, DepartureTimeSource.LIVE),
        )))
        assertEquals("14", groups[0].routeShortName)
        assertEquals(3L, groups[0].times[0].minutes)
    }

    @Test
    fun `batch - all stale stops make one network call`() = runTest {
        var batchCallCount = 0
        val state = computeTileState(
            favorites = listOf(stop(1L, 37.77, -122.41), stop(2L, 37.771, -122.41)),
            location = sfLocation,
            hasPermission = true,
            fetchDeparturesBatch = { stopIds ->
                batchCallCount++
                val now = System.currentTimeMillis()
                stopIds.associateWith { CachedStopFetch(listOf(cachedDep(5L)), emptyList(), now) }
            },
        )
        assertTrue(state is TileState.Ready)
        assertEquals(1, batchCallCount, "Should make exactly one batch call")
    }

    @Test
    fun `batch - all fresh stops make no network call`() = runTest {
        val now = System.currentTimeMillis()
        val cache = CachedTileData(
            lat = null, lon = null,
            nearbyDepartures = listOf(
                cachedStopDeps(1L, now - 30_000, 5L),
                cachedStopDeps(2L, now - 20_000, 10L),
            )
        )

        var batchCallCount = 0
        val state = computeTileState(
            favorites = listOf(stop(1L, 37.77, -122.41), stop(2L, 37.771, -122.41)),
            location = sfLocation,
            hasPermission = true,
            fetchDeparturesBatch = makeFetchNetworkDeparturesBatch(
                getDeparturesBatch = { stopIds ->
                    batchCallCount++
                    emptyMap()
                },
                cache = cache,
                stops = listOf(stop(1L, 37.77, -122.41), stop(2L, 37.771, -122.41)),
            ),
        )
        assertTrue(state is TileState.Ready)
        assertEquals(0, batchCallCount, "Should not make a batch call when all stops are fresh")
    }

    @Test
    fun `batch - maps departures to correct stop ids`() = runTest {
        val state = computeTileState(
            favorites = listOf(stop(1L, 37.77, -122.41), stop(2L, 37.771, -122.41)),
            location = sfLocation,
            hasPermission = true,
            fetchDeparturesBatch = { stopIds ->
                val now = System.currentTimeMillis()
                stopIds.associateWith { id ->
                    CachedStopFetch(listOf(cachedDep(5L + id)), emptyList(), now)
                }
            },
        )
        val ready = state as TileState.Ready
        assertEquals(6L, ready.stops.first { it.stop.id == 1L }.departures.first().currentMinutes())
        assertEquals(7L, ready.stops.first { it.stop.id == 2L }.departures.first().currentMinutes())
    }

    @Test
    fun `batch - empty departures for one stop still produces Ready`() = runTest {
        val state = computeTileState(
            favorites = listOf(stop(1L, 37.77, -122.41), stop(2L, 37.771, -122.41)),
            location = sfLocation,
            hasPermission = true,
            fetchDeparturesBatch = { stopIds ->
                val now = System.currentTimeMillis()
                mapOf(
                    1L to CachedStopFetch(listOf(cachedDep(5L)), emptyList(), now),
                    2L to CachedStopFetch(emptyList(), emptyList(), now),
                )
            },
        )
        val ready = state as TileState.Ready
        assertEquals(2, ready.stops.size, "Both stops should appear even with empty departures")
        assertEquals(1, ready.stops.count { it.departures.isNotEmpty() })
    }

    @Test
    fun `batch - network failure falls back to cache`() = runTest {
        val now = System.currentTimeMillis()
        val cache = CachedTileData(
            lat = null, lon = null,
            nearbyDepartures = listOf(cachedStopDeps(1L, now - 90_000, 5L)),
        )

        val state = computeTileState(
            favorites = listOf(stop(1L, 37.77, -122.41)),
            location = sfLocation,
            hasPermission = true,
            fetchDeparturesBatch = makeFetchNetworkDeparturesBatch(
                getDeparturesBatch = { throw RuntimeException("network error") },
                cache = cache,
                stops = listOf(stop(1L, 37.77, -122.41)),
            ),
        )
        val ready = state as TileState.Ready
        assertEquals(1, ready.stops.size)
    }

    @Test
    fun `makeFetchNetworkDeparturesBatch returns cache when fresh`() = runTest {
        val now = System.currentTimeMillis()
        val freshStop = cachedStopDeps(1L, now - 30_000, 5L, 10L)
        val cache = CachedTileData(lat = null, lon = null, nearbyDepartures = listOf(freshStop))

        var networkCalled = false
        val fetch = makeFetchNetworkDeparturesBatch(
            getDeparturesBatch = { networkCalled = true; emptyMap() },
            cache = cache,
            stops = listOf(stop(1L, 37.77, -122.41)),
            forceFresh = false,
        )

        val result = fetch(listOf(1L))
        assertFalse(networkCalled, "Network should not be called for fresh cache")
        assertEquals(2, result[1L]!!.departures.size)
    }

    @Test
    fun `makeFetchNetworkDeparturesBatch bypasses fresh cache when forceFresh`() = runTest {
        val now = System.currentTimeMillis()
        val freshStop = cachedStopDeps(1L, now - 10_000, 5L)
        val cache = CachedTileData(lat = null, lon = null, nearbyDepartures = listOf(freshStop))

        var networkCalled = false
        val fetch = makeFetchNetworkDeparturesBatch(
            getDeparturesBatch = { stopIds ->
                networkCalled = true
                stopIds.associateWith { _ ->
                    StopDepartures(0L, listOf(Departure(0L, "10:00", 5L, null, null, "10:00", 5L, DepartureTimeSource.SCHEDULED, "14", "Mission 14", "Ferry Plaza")))
                }
            },
            cache = cache,
            stops = listOf(stop(1L, 37.77, -122.41)),
            forceFresh = true,
        )

        fetch(listOf(1L))
        assertTrue(networkCalled, "Network should be called when forceFresh=true even with fresh cache")
    }

    @Test
    fun `makeFetchNetworkDeparturesBatch preserves grouping and sorting`() = runTest {
        val now = System.currentTimeMillis()
        val s1 = stop(1L, 37.770, -122.410)
        val s2 = stop(2L, 37.771, -122.410)
        val cache = CachedTileData(lat = null, lon = null, nearbyDepartures = emptyList())

        val state = computeTileState(
            favorites = listOf(s1, s2),
            location = sfLocation,
            hasPermission = true,
            fetchDeparturesBatch = makeFetchNetworkDeparturesBatch(
                getDeparturesBatch = { stopIds ->
                    stopIds.associateWith { _ ->
                        StopDepartures(0L, listOf(
                            Departure(0L, "10:00", 5L, null, null, "10:00", 5L, DepartureTimeSource.SCHEDULED, "14", "", "Ferry Plaza"),
                            Departure(0L, "10:15", 20L, null, null, "10:15", 20L, DepartureTimeSource.SCHEDULED, "14", "", "Ferry Plaza"),
                        ))
                    }
                },
                cache = cache,
                stops = listOf(s1, s2),
                forceFresh = true,
            ),
        )
        val ready = state as TileState.Ready
        val groups = groupDepartures(ready.stops)
        assertTrue(groups.all { it.showStopTag })
    }
}
