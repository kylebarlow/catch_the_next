package dev.catchthenext.android.ui

import app.cash.turbine.test
import dev.catchthenext.android.sync.PushResult
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
        pushToPeer: suspend () -> PushResult = { PushResult.Sent },
    ): SettingsViewModel {
        return SettingsViewModel(
            distanceUnitFlow = flowOf(),
            persistUnit = {},
            thresholdMetersFlow = flowOf(),
            persistThreshold = {},
            peerLabel = peerLabel,
            pushToPeer = pushToPeer,
            ioDispatcher = testDispatcher,
        )
    }

    @Test fun `syncNow transitions IDLE to PUSHING to SENT then back to IDLE`() = runTest {
        val vm = makeVm(pushToPeer = { delay(100); PushResult.Sent })
        vm.syncStatus.test {
            assertEquals(SyncStatus.IDLE, awaitItem())
            vm.syncNow()
            assertEquals(SyncStatus.PUSHING, awaitItem())
            advanceTimeBy(101)
            assertEquals(SyncStatus.SENT, awaitItem())
            advanceTimeBy(2_001)
            assertEquals(SyncStatus.IDLE, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `syncNow emits Sent to peer event on success`() = runTest {
        val vm = makeVm(peerLabel = "watch", pushToPeer = { delay(1); PushResult.Sent })
        vm.syncEvents.test {
            vm.syncNow()
            advanceTimeBy(2)
            assertEquals("Sent to watch", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `syncNow transitions to PEER_UNREACHABLE when peer is not reachable`() = runTest {
        val vm = makeVm(pushToPeer = { delay(100); PushResult.PeerUnreachable })
        vm.syncStatus.test {
            assertEquals(SyncStatus.IDLE, awaitItem())
            vm.syncNow()
            assertEquals(SyncStatus.PUSHING, awaitItem())
            advanceTimeBy(101)
            assertEquals(SyncStatus.PEER_UNREACHABLE, awaitItem())
            advanceTimeBy(2_001)
            assertEquals(SyncStatus.IDLE, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `syncNow emits peer unreachable event with label`() = runTest {
        val vm = makeVm(peerLabel = "phone", pushToPeer = { delay(1); PushResult.PeerUnreachable })
        vm.syncEvents.test {
            vm.syncNow()
            advanceTimeBy(2)
            assertEquals("phone unreachable", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `syncNow transitions to ERROR when pushToPeer returns Failed`() = runTest {
        val vm = makeVm(pushToPeer = { delay(100); PushResult.Failed("network error") })
        vm.syncStatus.test {
            assertEquals(SyncStatus.IDLE, awaitItem())
            vm.syncNow()
            assertEquals(SyncStatus.PUSHING, awaitItem())
            advanceTimeBy(101)
            assertEquals(SyncStatus.ERROR, awaitItem())
            advanceTimeBy(2_001)
            assertEquals(SyncStatus.IDLE, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `syncNow transitions to ERROR when pushToPeer throws`() = runTest {
        val vm = makeVm(pushToPeer = { delay(100); error("unexpected crash") })
        vm.syncStatus.test {
            assertEquals(SyncStatus.IDLE, awaitItem())
            vm.syncNow()
            assertEquals(SyncStatus.PUSHING, awaitItem())
            advanceTimeBy(101)
            assertEquals(SyncStatus.ERROR, awaitItem())
            advanceTimeBy(2_001)
            assertEquals(SyncStatus.IDLE, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `syncNow emits Failed event with reason`() = runTest {
        val vm = makeVm(pushToPeer = { delay(1); PushResult.Failed("timeout") })
        vm.syncEvents.test {
            vm.syncNow()
            advanceTimeBy(2)
            assertEquals("Failed: timeout", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
