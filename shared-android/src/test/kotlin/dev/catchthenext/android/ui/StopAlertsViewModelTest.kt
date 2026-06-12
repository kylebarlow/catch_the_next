package dev.catchthenext.android.ui

import dev.catchthenext.model.Alert
import dev.catchthenext.model.AlertSeverity
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
class StopAlertsViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()

    private val alert = Alert(
        cause = null,
        effect = null,
        severityLevel = AlertSeverity.WARNING,
        headerText = "Delays on Line 1",
        descriptionText = "Expect 10 minute delays.",
    )

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `cache hit exposes stop name and alerts`() = runTest {
        val vm = StopAlertsViewModel(
            stopId = 1L,
            readAlerts = { listOf(alert) },
            getStopName = { "Stop One" },
            ioDispatcher = testDispatcher,
        )
        val ui = vm.ui.value
        assertEquals("Stop One", ui.stopName)
        assertEquals(listOf(alert), ui.alerts)
    }

    @Test
    fun `missing cache entry yields empty alerts`() = runTest {
        val vm = StopAlertsViewModel(
            stopId = 99L,
            readAlerts = { emptyList() },
            getStopName = { null },
            ioDispatcher = testDispatcher,
        )
        val ui = vm.ui.value
        assertEquals(null, ui.stopName)
        assertTrue(ui.alerts.isEmpty())
    }
}
