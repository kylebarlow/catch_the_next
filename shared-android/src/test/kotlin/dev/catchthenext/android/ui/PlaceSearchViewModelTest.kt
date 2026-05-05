package dev.catchthenext.android.ui

import app.cash.turbine.test
import dev.catchthenext.model.Place
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaceSearchViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()

    private val place1 = Place("1", "Berkeley, CA", 37.87, -122.27, "boundary", "administrative")

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun makeVm(
        places: List<Place> = listOf(place1),
        debounceMs: Long = 0L,
    ) = PlaceSearchViewModel(
        geocode = { _, _, _ -> places },
        ioDispatcher = testDispatcher,
        debounceMs = debounceMs,
    )

    @Test
    fun `initial state is Idle`() {
        val vm = makeVm()
        assertTrue(vm.ui.value is PlaceSearchUi.Idle)
    }

    @Test
    fun `query shorter than 3 chars stays Idle`() = runTest {
        val vm = makeVm()
        vm.onQueryChanged("ab")
        assertTrue(vm.ui.value is PlaceSearchUi.Idle)
    }

    @Test
    fun `empty query stays Idle`() = runTest {
        val vm = makeVm()
        vm.onQueryChanged("")
        assertTrue(vm.ui.value is PlaceSearchUi.Idle)
    }

    @Test
    fun `query of 3+ chars emits Results`() = runTest {
        val vm = makeVm()
        vm.onQueryChanged("Berkeley")
        val state = vm.ui.value
        assertTrue(state is PlaceSearchUi.Results, "Expected Results but got $state")
        assertEquals(1, (state as PlaceSearchUi.Results).places.size)
        assertEquals("Berkeley, CA", state.places[0].displayName)
    }

    @Test
    fun `empty geocode result emits NoResults`() = runTest {
        val vm = PlaceSearchViewModel(
            geocode = { _, _, _ -> emptyList() },
            ioDispatcher = testDispatcher,
            debounceMs = 0L,
        )
        vm.onQueryChanged("xyzzy")
        assertTrue(vm.ui.value is PlaceSearchUi.NoResults)
    }

    @Test
    fun `geocode error emits Error state`() = runTest {
        val vm = PlaceSearchViewModel(
            geocode = { _, _, _ -> throw RuntimeException("network failure") },
            ioDispatcher = testDispatcher,
            debounceMs = 0L,
        )
        vm.onQueryChanged("Berkeley")
        val state = vm.ui.value
        assertTrue(state is PlaceSearchUi.Error, "Expected Error but got $state")
        assertEquals("network failure", (state as PlaceSearchUi.Error).msg)
    }

    @Test
    fun `rapid queries are debounced — only last fires`() = runTest {
        var callCount = 0
        val vm = PlaceSearchViewModel(
            geocode = { q, _, _ -> callCount++; listOf(Place(q, q, 0.0, 0.0)) },
            ioDispatcher = testDispatcher,
            debounceMs = 300L,
        )
        vm.ui.test {
            awaitItem() // Idle
            vm.onQueryChanged("ber")
            vm.onQueryChanged("berk")
            vm.onQueryChanged("berke")
            advanceTimeBy(350L)
            // Only the last query should have fired
            val last = awaitItem()
            assertTrue(last is PlaceSearchUi.Searching || last is PlaceSearchUi.Results || last is PlaceSearchUi.NoResults)
            assertTrue(callCount <= 1, "Expected ≤1 geocode call due to debounce, got $callCount")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `switching back to short query resets to Idle`() = runTest {
        val vm = makeVm()
        vm.onQueryChanged("Berkeley")
        assertTrue(vm.ui.value is PlaceSearchUi.Results)
        vm.onQueryChanged("Be")
        assertTrue(vm.ui.value is PlaceSearchUi.Idle)
    }
}
