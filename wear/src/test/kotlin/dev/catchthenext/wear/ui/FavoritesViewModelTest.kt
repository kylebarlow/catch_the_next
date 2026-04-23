package dev.catchthenext.wear.ui

import app.cash.turbine.test
import dev.catchthenext.model.Stop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FavoritesViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()

    private val stop1 = Stop(1L, "S1", "Stop One", 37.77, -122.41)
    private val stop2 = Stop(2L, "S2", "Stop Two", 37.78, -122.41)

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial load emits saved stops`() = runTest {
        val fake = FakeFavoritesManager(listOf(stop1, stop2))
        val vm = FavoritesViewModel(fake, testDispatcher)

        vm.favorites.test {
            val items = awaitItem()
            assertEquals(2, items.size)
            assertEquals("Stop One", items[0].stopName)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `initial load emits empty list when no favorites`() = runTest {
        val fake = FakeFavoritesManager()
        val vm = FavoritesViewModel(fake, testDispatcher)

        vm.favorites.test {
            assertEquals(emptyList<Stop>(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `removeFavorite removes stop from flow and storage`() = runTest {
        val fake = FakeFavoritesManager(listOf(stop1, stop2))
        val vm = FavoritesViewModel(fake, testDispatcher)

        vm.removeFavorite(stop1.id)

        val items = vm.favorites.value
        assertEquals(1, items.size)
        assertEquals(stop2.id, items[0].id)
        assertFalse(fake.isFavorite(stop1.id))
    }

    @Test
    fun `refresh reloads from storage`() = runTest {
        val fake = FakeFavoritesManager(listOf(stop1))
        val vm = FavoritesViewModel(fake, testDispatcher)

        assertEquals(1, vm.favorites.value.size)

        fake.addFavorite(stop2)
        vm.refresh()

        assertEquals(2, vm.favorites.value.size)
    }
}
