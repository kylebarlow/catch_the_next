package dev.catchthenext.android.ui

import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()

    @BeforeEach fun setUp() { Dispatchers.setMain(testDispatcher) }
    @AfterEach fun tearDown() { Dispatchers.resetMain() }

    private fun makeVm(
        peerLabel: String = "watch",
        doSync: suspend () -> Unit = {},
        peerReachable: Boolean = true,
    ): SettingsViewModel {
        return SettingsViewModel(
            distanceUnitFlow = flowOf(),
            persistUnit = {},
            thresholdMetersFlow = flowOf(),
            persistThreshold = {},
            peerLabel = peerLabel,
            doSync = doSync,
            peerReachableFlow = flowOf(peerReachable),
            ioDispatcher = testDispatcher,
        )
    }

    @Test fun `syncNow transitions IDLE to SYNCING to DONE then back to IDLE`() = runTest {
        val vm = makeVm(doSync = { delay(100) })
        vm.syncStatus.test {
            assertEquals(SyncStatus.IDLE, awaitItem())
            vm.syncNow()
            assertEquals(SyncStatus.SYNCING, awaitItem())
            advanceTimeBy(101)
            assertEquals(SyncStatus.DONE, awaitItem())
            advanceTimeBy(2_001)
            assertEquals(SyncStatus.IDLE, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `syncNow emits Synced event on success when peer reachable`() = runTest {
        val vm = makeVm(peerLabel = "watch", doSync = { delay(1) }, peerReachable = true)
        vm.syncEvents.test {
            vm.syncNow()
            advanceTimeBy(2)
            assertEquals("Synced", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `syncNow emits offline suffix when peer not reachable`() = runTest {
        val vm = makeVm(peerLabel = "watch", doSync = { delay(1) }, peerReachable = false)
        vm.syncEvents.test {
            vm.syncNow()
            advanceTimeBy(2)
            val event = awaitItem()
            assertTrue(event.contains("offline"))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `syncNow transitions to ERROR when doSync throws`() = runTest {
        val vm = makeVm(doSync = { delay(100); error("network error") })
        vm.syncStatus.test {
            assertEquals(SyncStatus.IDLE, awaitItem())
            vm.syncNow()
            assertEquals(SyncStatus.SYNCING, awaitItem())
            advanceTimeBy(101)
            assertEquals(SyncStatus.ERROR, awaitItem())
            advanceTimeBy(2_001)
            assertEquals(SyncStatus.IDLE, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `syncNow emits Failed event with message on error`() = runTest {
        val vm = makeVm(doSync = { delay(1); error("timeout") })
        vm.syncEvents.test {
            vm.syncNow()
            advanceTimeBy(2)
            val event = awaitItem()
            assertTrue(event.contains("timeout"))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `peerReachable reflects provided flow`() = runTest {
        val vm = makeVm(peerReachable = false)
        assertEquals(false, vm.peerReachable.value)
    }

    private fun assertTrue(condition: Boolean) = org.junit.jupiter.api.Assertions.assertTrue(condition)
}
