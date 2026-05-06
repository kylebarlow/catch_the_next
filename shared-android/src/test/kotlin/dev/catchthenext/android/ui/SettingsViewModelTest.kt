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

    private fun makeVm(triggerSync: suspend () -> Unit = {}): SettingsViewModel {
        return SettingsViewModel(
            distanceUnitFlow = flowOf(),
            persistUnit = {},
            thresholdMetersFlow = flowOf(),
            persistThreshold = {},
            triggerSync = triggerSync,
            ioDispatcher = testDispatcher,
        )
    }

    @Test fun `syncNow transitions IDLE to SYNCING to SUCCESS then back to IDLE`() = runTest {
        // delay in triggerSync gives turbine a chance to observe SYNCING before SUCCESS is set
        val vm = makeVm(triggerSync = { delay(100) })
        vm.syncStatus.test {
            assertEquals(SyncStatus.IDLE, awaitItem())
            vm.syncNow()
            assertEquals(SyncStatus.SYNCING, awaitItem())
            advanceTimeBy(101)
            assertEquals(SyncStatus.SUCCESS, awaitItem())
            advanceTimeBy(2_001)
            assertEquals(SyncStatus.IDLE, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `syncNow emits Synced event on success`() = runTest {
        val vm = makeVm(triggerSync = { delay(1) })
        vm.syncEvents.test {
            vm.syncNow()
            advanceTimeBy(2)
            assertEquals("Synced", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `syncNow transitions to ERROR when triggerSync throws`() = runTest {
        val vm = makeVm(triggerSync = { delay(100); error("network failure") })
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

    @Test fun `syncNow emits Sync failed event on error`() = runTest {
        val vm = makeVm(triggerSync = { delay(1); error("network failure") })
        vm.syncEvents.test {
            vm.syncNow()
            advanceTimeBy(2)
            assertEquals("Sync failed", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
