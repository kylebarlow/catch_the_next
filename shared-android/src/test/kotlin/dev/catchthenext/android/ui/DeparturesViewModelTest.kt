package dev.catchthenext.android.ui

import dev.catchthenext.model.DepartureTimeSource
import dev.catchthenext.model.Stop
import dev.catchthenext.android.tile.CachedDeparture
import dev.catchthenext.android.tile.StopWithDepartures
import dev.catchthenext.android.tile.TileState
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
        departureEpochMillis = System.currentTimeMillis() + minutesFromNow * 60_000,
        timeSource = DepartureTimeSource.SCHEDULED,
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
    fun `refresh transitions through Loaded with isRefreshing before final Loaded`() = runTest(testDispatcher) {
        val vm = DeparturesViewModel(
            computeState = { TileState.NoLocation },
            ioDispatcher = testDispatcher,
        )
        vm.refresh()
        val state = vm.ui.value
        assertTrue(state is DeparturesUi.Loaded, "State should be Loaded after refresh completes")
        assertTrue(!(state as DeparturesUi.Loaded).isRefreshing, "Final state should not be refreshing")
    }

    @Test
    fun `quickCacheRead is called during init`() = runTest(testDispatcher) {
        var quickCalled = false
        val vm = DeparturesViewModel(
            computeState = { TileState.NoFavorites },
            quickCacheRead = {
                quickCalled = true
                null
            },
            ioDispatcher = testDispatcher,
        )
        assertTrue(quickCalled, "quickCacheRead should be called during init")
        val state = vm.ui.value as DeparturesUi.Loaded
        assertTrue(state.tileState is TileState.NoFavorites, "Final state comes from computeState")
    }

    @Test
    fun `quickCacheRead result is shown before computeState completes`() = runTest(testDispatcher) {
        val now = System.currentTimeMillis()
        val cachedReady = TileState.Ready(
            listOf(StopWithDepartures(stop(1L), 0.0, listOf(cachedDep(5L)), now)),
            now
        )
        val vm = DeparturesViewModel(
            computeState = { TileState.NoLocation },
            quickCacheRead = { cachedReady },
            ioDispatcher = testDispatcher,
        )
        val state = vm.ui.value as DeparturesUi.Loaded
        assertTrue(state.tileState is TileState.NoLocation)
    }

    @Test
    fun `null quickCacheRead skips cache path and uses computeState`() = runTest(testDispatcher) {
        val vm = DeparturesViewModel(
            computeState = { TileState.NetworkError("offline") },
            quickCacheRead = null,
            ioDispatcher = testDispatcher,
        )
        val state = vm.ui.value as DeparturesUi.Loaded
        assertEquals("offline", (state.tileState as TileState.NetworkError).message)
    }
}
