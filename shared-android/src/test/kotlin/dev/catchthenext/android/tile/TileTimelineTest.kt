package dev.catchthenext.android.tile

import dev.catchthenext.model.Alert
import dev.catchthenext.model.DepartureTimeSource
import dev.catchthenext.model.Stop
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TileTimelineTest {

    private val now = System.currentTimeMillis()

    private fun stop(id: Long) = Stop(id, "S$id", "Stop $id", 37.770, -122.410, onestopId = "s-$id")

    // groupDepartures reads the wall clock, so pad each instant by half a minute to keep the
    // integer-minute results stable no matter how long the test takes.
    private fun dep(minutesFromNow: Long, route: String = "14") = CachedDeparture(
        routeShortName = route,
        headsign = "Ferry Plaza",
        departureEpochMillis = now + minutesFromNow * 60_000 + 30_000,
        timeSource = DepartureTimeSource.SCHEDULED,
    )

    private fun ready(
        deps: List<CachedDeparture>,
        alerts: List<Alert> = emptyList(),
        isStale: Boolean = false,
        fetchedAt: Long = now - 10_000,
    ) = TileState.Ready(
        listOf(StopWithDepartures(stop(1L), 0.0, deps, fetchedAt, alerts, isStale)),
        fetchedAt,
    )

    @Test
    fun `one slice per distinct departure instant plus a terminal slice`() {
        val deps = listOf(dep(3), dep(8, "22"), dep(8, "33"), dep(15))
        val slices = buildTimelineSlices(ready(deps), now)

        assertEquals(4, slices.size, "3 distinct instants + terminal")
        assertEquals(4, slices[0].stops[0].departures.size, "The first slice shows everything")
        assertEquals(3, slices[1].stops[0].departures.size, "The +3 departure has gone")
        assertEquals(1, slices[2].stops[0].departures.size, "Both +8 departures have gone")
        assertTrue(slices[3].stops.all { it.departures.isEmpty() }, "The terminal slice is empty")
        assertNull(slices[3].validUntilMillis, "The terminal slice never expires")
    }

    @Test
    fun `each slice is valid until its departure plus the now grace`() {
        val deps = listOf(dep(3), dep(15))
        val slices = buildTimelineSlices(ready(deps), now)
        assertEquals(deps[0].departureEpochMillis + Tuning.TILE_NOW_GRACE_MS, slices[0].validUntilMillis)
        assertEquals(deps[1].departureEpochMillis + Tuning.TILE_NOW_GRACE_MS, slices[1].validUntilMillis)
    }

    @Test
    fun `slices are capped at maxSlices with the remainder in the terminal slice`() {
        // 20 distinct instants across enough routes to survive the maxGroups / maxPerGroup caps.
        val deps = (1..20).map { dep(it.toLong(), route = "R${it % 7}") }
        val slices = buildTimelineSlices(ready(deps), now, maxGroups = 7, maxPerGroup = 3, maxSlices = 8)
        assertEquals(8, slices.size)
        assertNull(slices.last().validUntilMillis)
    }

    @Test
    fun `departures the tile does not display create no boundary`() {
        // maxPerGroup = 1 hides the +8 and +15 departures of the single group.
        val deps = listOf(dep(3), dep(8), dep(15))
        val slices = buildTimelineSlices(ready(deps), now, maxPerGroup = 1)
        assertEquals(2, slices.size, "Only the displayed +3 departure makes a boundary")
    }

    @Test
    fun `departures beyond the tile horizon create no boundary`() {
        val deps = listOf(dep(3), dep(90))
        val slices = buildTimelineSlices(ready(deps), now)
        assertEquals(2, slices.size, "The +90 departure is past the one-hour horizon")
    }

    @Test
    fun `departures already in the past create no boundary`() {
        val deps = listOf(dep(-5), dep(3))
        val slices = buildTimelineSlices(ready(deps), now)
        assertEquals(2, slices.size)
    }

    @Test
    fun `alerts staleness and fetchedAt survive on every slice`() {
        val alerts = listOf(Alert(cause = null, effect = null, headerText = "Delays", descriptionText = null))
        val fetchedAt = now - 120_000
        val slices = buildTimelineSlices(
            ready(listOf(dep(3), dep(8)), alerts = alerts, isStale = true, fetchedAt = fetchedAt),
            now,
        )
        slices.forEach { slice ->
            val swd = slice.stops.single()
            assertEquals(alerts, swd.alerts)
            assertTrue(swd.isStale)
            assertEquals(fetchedAt, swd.fetchedAt)
            assertEquals(1L, swd.stop.id)
        }
    }

    @Test
    fun `an empty ready state yields a single terminal slice`() {
        val slices = buildTimelineSlices(ready(emptyList()), now)
        assertEquals(1, slices.size)
        assertNull(slices[0].validUntilMillis)
    }
}
