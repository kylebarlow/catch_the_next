package dev.catchthenext.android.ui

import dev.catchthenext.model.DepartureTimeSource
import dev.catchthenext.model.Stop
import dev.catchthenext.android.tile.CachedDeparture
import dev.catchthenext.android.tile.StopWithDepartures
import dev.catchthenext.android.tile.TileState
import dev.catchthenext.android.tile.Tuning
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
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
        var clock = 0L
        val vm = DeparturesViewModel(
            computeState = { force ->
                receivedForce = force
                TileState.NoFavorites
            },
            ioDispatcher = testDispatcher,
            now = { clock },
        )
        clock += Tuning.VM_REFRESH_DEBOUNCE_MS
        vm.refresh()
        assertTrue(!receivedForce, "computeState should receive force=false by default")
    }

    @Test
    fun `two non-forced refreshes within the debounce window run computeState once`() = runTest(testDispatcher) {
        var runs = 0
        var clock = 0L
        val vm = DeparturesViewModel(
            computeState = { runs++; TileState.NoFavorites },
            ioDispatcher = testDispatcher,
            now = { clock },
        )
        assertEquals(1, runs, "The init pass always runs")
        clock += Tuning.VM_REFRESH_DEBOUNCE_MS - 1
        vm.refresh()
        vm.refresh()
        assertEquals(1, runs, "Both refreshes fall inside the debounce window")
    }

    @Test
    fun `a non-forced refresh past the debounce window runs again`() = runTest(testDispatcher) {
        var runs = 0
        var clock = 0L
        val vm = DeparturesViewModel(
            computeState = { runs++; TileState.NoFavorites },
            ioDispatcher = testDispatcher,
            now = { clock },
        )
        clock += Tuning.VM_REFRESH_DEBOUNCE_MS
        vm.refresh()
        assertEquals(2, runs)
    }

    @Test
    fun `force bypasses the debounce window`() = runTest(testDispatcher) {
        var runs = 0
        val vm = DeparturesViewModel(
            computeState = { runs++; TileState.NoFavorites },
            ioDispatcher = testDispatcher,
            now = { 0L },
        )
        vm.refresh(force = true)
        assertEquals(2, runs, "force=true runs even inside the debounce window")
    }

    @Test
    fun `a favorites count change bypasses the debounce window`() = runTest(testDispatcher) {
        var runs = 0
        val counts = MutableStateFlow(0)
        val vm = DeparturesViewModel(
            computeState = { runs++; TileState.NoFavorites },
            favoritesCountFlow = counts,
            ioDispatcher = testDispatcher,
            now = { 0L },
        )
        assertEquals(1, runs)
        counts.value = 2
        assertEquals(2, runs, "A favorites change re-runs even inside the debounce window")
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
        // Hold computeState open so the state published from the cache can be observed.
        val gate = CompletableDeferred<Unit>()
        val vm = DeparturesViewModel(
            computeState = { gate.await(); TileState.NoLocation },
            quickCacheRead = { cachedReady },
            ioDispatcher = testDispatcher,
        )
        val duringCompute = vm.ui.value as DeparturesUi.Loaded
        assertEquals(cachedReady, duringCompute.tileState)
        assertTrue(duringCompute.isRefreshing, "The quick state is shown with the refresh spinner")

        gate.complete(Unit)
        val state = vm.ui.value as DeparturesUi.Loaded
        assertTrue(state.tileState is TileState.NoLocation)
        assertTrue(!state.isRefreshing, "Final state is not refreshing")
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
