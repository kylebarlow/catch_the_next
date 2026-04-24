package dev.catchthenext.wear.ui

import app.cash.turbine.test
import dev.catchthenext.model.Stop
import dev.catchthenext.wear.location.LatLon
import dev.catchthenext.wear.storage.DistanceUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FavoritesViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()

    private val stop1 = Stop(1L, "S1", "Stop One", 37.77, -122.41)
    private val stop2 = Stop(2L, "S2", "Stop Two", 37.78, -122.41)
    private val fakeLocation = LatLon(37.77, -122.41)

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun makeVm(
        initial: List<Stop> = listOf(stop1, stop2),
        location: LatLon? = fakeLocation,
        highAccuracyLocation: LatLon? = fakeLocation,
    ): Pair<FakeFavoritesManager, FavoritesViewModel> {
        val fake = FakeFavoritesManager(initial)
        val vm = FavoritesViewModel(
            favoritesFlow = fake.favoritesFlow(),
            favoritesManager = fake,
            locationProvider = { location },
            highAccuracyLocate = { highAccuracyLocation },
            distanceUnitFlow = flowOf(DistanceUnit.MILES),
            persistUnit = {},
            ioDispatcher = testDispatcher,
        )
        return fake to vm
    }

    @Test
    fun `initial load emits saved stops`() = runTest {
        val (_, vm) = makeVm()
        vm.favorites.test {
            val items = awaitItem()
            assertEquals(2, items.size)
            assertEquals("Stop One", items[0].stopName)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `initial load emits empty list when no favorites`() = runTest {
        val (_, vm) = makeVm(initial = emptyList())
        vm.favorites.test {
            assertEquals(emptyList<Stop>(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `removeFavorite removes stop from flow and storage`() = runTest {
        val (fake, vm) = makeVm()
        vm.favorites.test {
            awaitItem() // initial list
            vm.removeFavorite(stop1.id)
            val items = awaitItem()
            assertEquals(1, items.size)
            assertEquals(stop2.id, items[0].id)
            assertFalse(fake.isFavorite(stop1.id))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `location is fetched on init and age is recorded`() = runTest {
        val (_, vm) = makeVm()
        assertEquals(fakeLocation, vm.location.value)
        assertNotNull(vm.locationFetchedAt.value)
    }

    @Test
    fun `null location leaves fetchedAt null`() = runTest {
        val (_, vm) = makeVm(location = null)
        assertNull(vm.location.value)
        assertNull(vm.locationFetchedAt.value)
    }

    @Test
    fun `refreshLocation uses high-accuracy provider`() = runTest {
        val highAccuracyResult = LatLon(40.0, -74.0)
        var callCount = 0
        val fake = FakeFavoritesManager()
        val vm = FavoritesViewModel(
            favoritesFlow = fake.favoritesFlow(),
            favoritesManager = fake,
            locationProvider = { fakeLocation },
            highAccuracyLocate = { callCount++; highAccuracyResult },
            distanceUnitFlow = flowOf(DistanceUnit.MILES),
            persistUnit = {},
            ioDispatcher = testDispatcher,
        )
        vm.refreshLocation()
        assertEquals(highAccuracyResult, vm.location.value)
        assertEquals(1, callCount)
    }
}
