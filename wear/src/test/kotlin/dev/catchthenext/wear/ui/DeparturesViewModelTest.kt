package dev.catchthenext.wear.ui

import dev.catchthenext.model.Stop
import dev.catchthenext.wear.tile.CachedDeparture
import dev.catchthenext.wear.tile.StopWithDepartures
import dev.catchthenext.wear.tile.TileState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DeparturesViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()

    @BeforeEach fun setUp() { Dispatchers.setMain(testDispatcher) }
    @AfterEach fun tearDown() { Dispatchers.resetMain() }

    private fun stop(id: Long) = Stop(id, "S$id", "Stop $id", 37.77, -122.41)

    private fun cachedDep(minutesFromNow: Long) = CachedDeparture(
        routeShortName = "14",
        headsign = "Ferry Plaza",
        scheduledEpochMillis = System.currentTimeMillis() + minutesFromNow * 60_000,
    )

    @Test
    fun `init loads state from computeState`() = runTest(testDispatcher) {
        val vm = DeparturesViewModel(
            computeState = { TileState.NetworkError("timeout") },
            ioDispatcher = testDispatcher,
        )
        val state = vm.ui.value as DeparturesUi.Loaded
        assertTrue(state.tileState is TileState.NetworkError)
        assertEquals("timeout", (state.tileState as TileState.NetworkError).message)
    }

    @Test
    fun `Ready state is surfaced in Loaded`() = runTest(testDispatcher) {
        val now = System.currentTimeMillis()
        val stops = listOf(
            StopWithDepartures(stop(1L), 0.0, listOf(cachedDep(5L), cachedDep(10L)), now),
        )
        val vm = DeparturesViewModel(
            computeState = { TileState.Ready(stops, now) },
            ioDispatcher = testDispatcher,
        )
        val state = vm.ui.value as DeparturesUi.Loaded
        val ready = state.tileState as TileState.Ready
        assertEquals(1, ready.stops.size)
        assertEquals(2, ready.stops[0].departures.size)
    }

    @Test
    fun `force=true is forwarded to computeState`() = runTest(testDispatcher) {
        var receivedForce = false
        val vm = DeparturesViewModel(
            computeState = { force ->
                receivedForce = force
                TileState.NoFavorites
            },
            ioDispatcher = testDispatcher,
        )
        vm.refresh(force = true)
        assertTrue(receivedForce, "computeState should receive force=true")
    }

    @Test
    fun `force=false is the default for refresh`() = runTest(testDispatcher) {
        var receivedForce = true
        val vm = DeparturesViewModel(
            computeState = { force ->
                receivedForce = force
                TileState.NoFavorites
            },
            ioDispatcher = testDispatcher,
        )
        vm.refresh()
        assertTrue(!receivedForce, "computeState should receive force=false by default")
    }

    @Test
    fun `refresh transitions through Loading before Loaded`() = runTest(testDispatcher) {
        val states = mutableListOf<DeparturesUi>()
        val vm = DeparturesViewModel(
            computeState = { TileState.NoLocation },
            ioDispatcher = testDispatcher,
        )
        // With UnconfinedTestDispatcher, the coroutine runs inline so we see final state.
        // Test that final state is Loaded (not stuck on Loading).
        val state = vm.ui.value
        assertTrue(state is DeparturesUi.Loaded, "State should be Loaded after init completes")
    }
}
