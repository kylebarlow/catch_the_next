package dev.catchthenext.android.ui

import app.cash.turbine.test
import dev.catchthenext.model.Stop
import dev.catchthenext.android.location.LatLon
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
class AddStopViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()

    private val stop1 = Stop(1L, "S1", "Stop One", 37.77, -122.41, onestopId = "s-stop1")
    private val stop2 = Stop(2L, "S2", "Stop Two", 37.78, -122.41, onestopId = "s-stop2")
    private val fakeLocation = LatLon(37.77, -122.41)

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private var lastRadius: Int? = null

    private fun makeVm(
        stops: List<Stop> = listOf(stop1, stop2),
        location: LatLon? = fakeLocation,
        manager: FakeFavoritesManager = FakeFavoritesManager(),
    ) = AddStopViewModel(
        getNearbyStops = { _, _, r -> lastRadius = r; stops },
        favoritesManager = manager,
        locationProvider = { location },
        ioDispatcher = testDispatcher,
    )

    @Test
    fun `starts in PermissionNeeded state`() {
        val vm = makeVm()
        assertTrue(vm.ui.value is AddStopUi.PermissionNeeded)
    }

    @Test
    fun `onPermissionGranted with location transitions to Loaded`() = runTest {
        val vm = makeVm()
        vm.ui.test {
            assertEquals(AddStopUi.PermissionNeeded, awaitItem())
            vm.onPermissionGranted()
            val emissions = mutableListOf<AddStopUi>()
            while (true) {
                val item = awaitItem()
                emissions.add(item)
                if (item is AddStopUi.Loaded || item is AddStopUi.Error) break
            }
            val loaded = emissions.last()
            assertTrue(loaded is AddStopUi.Loaded, "Expected Loaded but got $loaded")
            assertEquals(2, (loaded as AddStopUi.Loaded).stops.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onPermissionGranted with null location transitions to Error`() = runTest {
        val vm = makeVm(location = null)
        vm.onPermissionGranted()
        val state = vm.ui.value
        assertTrue(state is AddStopUi.Error, "Expected Error but got $state")
    }

    @Test
    fun `onPermissionGranted with no nearby stops transitions to Empty`() = runTest {
        val vm = makeVm(stops = emptyList())
        vm.onPermissionGranted()
        assertEquals(AddStopUi.Empty, vm.ui.value)
    }

    @Test
    fun `onPermissionGranted when fetcher throws transitions to Error`() = runTest {
        val vm = AddStopViewModel(
            getNearbyStops = { _, _, _ -> throw RuntimeException("network failure") },
            favoritesManager = FakeFavoritesManager(),
            locationProvider = { fakeLocation },
            ioDispatcher = testDispatcher,
        )
        vm.onPermissionGranted()
        val state = vm.ui.value
        assertTrue(state is AddStopUi.Error)
        assertEquals("network failure", (state as AddStopUi.Error).msg)
    }

    @Test
    fun `addStop saves to favorites manager`() = runTest {
        val manager = FakeFavoritesManager()
        val vm = makeVm(manager = manager)
        vm.addStop(stop1)
        assertTrue(manager.isFavorite(stop1.onestopId!!))
    }

    @Test
    fun `loadFor skips location provider and emits Loaded`() = runTest {
        val vm = makeVm()
        vm.loadFor(fakeLocation)
        val state = vm.ui.value
        assertTrue(state is AddStopUi.Loaded, "Expected Loaded but got $state")
        assertEquals(2, (state as AddStopUi.Loaded).stops.size)
    }

    @Test
    fun `loadFor forwards custom radius`() = runTest {
        val vm = makeVm()
        vm.loadFor(fakeLocation, radiusMeters = 3000)
        assertEquals(3000, lastRadius)
    }

    @Test
    fun `loadFor emits Error on network failure`() = runTest {
        val vm = AddStopViewModel(
            getNearbyStops = { _, _, _ -> throw RuntimeException("timeout") },
            favoritesManager = FakeFavoritesManager(),
            locationProvider = { fakeLocation },
            ioDispatcher = testDispatcher,
        )
        vm.loadFor(fakeLocation)
        val state = vm.ui.value
        assertTrue(state is AddStopUi.Error)
        assertEquals("timeout", (state as AddStopUi.Error).msg)
    }

    @Test
    fun `onPermissionGranted uses 600m radius`() = runTest {
        val vm = makeVm()
        vm.onPermissionGranted()
        assertEquals(600, lastRadius)
    }
}
