package dev.catchthenext.android.ui

import dev.catchthenext.model.Alert
import dev.catchthenext.model.AlertSeverity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class AlertPresentationTest {

    private fun alert(
        severity: AlertSeverity = AlertSeverity.UNKNOWN_SEVERITY,
        header: String? = "Header",
        description: String? = "Description",
        url: String? = null,
    ) = Alert(
        cause = null,
        effect = null,
        severityLevel = severity,
        headerText = header,
        descriptionText = description,
        url = url,
    )

    @Test
    fun `emphasis maps severity to emphasis`() {
        assertEquals(AlertEmphasis.CRITICAL, alert(severity = AlertSeverity.SEVERE).emphasis())
        assertEquals(AlertEmphasis.ELEVATED, alert(severity = AlertSeverity.WARNING).emphasis())
        assertEquals(AlertEmphasis.NORMAL, alert(severity = AlertSeverity.INFO).emphasis())
        assertEquals(AlertEmphasis.NORMAL, alert(severity = AlertSeverity.UNKNOWN_SEVERITY).emphasis())
    }

    @Test
    fun `display normalizes header and blanks`() {
        val d = alert(header = null, description = "  ", url = "  ").display()
        assertEquals("", d.header)
        assertNull(d.description)
        assertNull(d.url)
    }

    @Test
    fun `display passes through populated fields`() {
        val d = alert(header = "Delay", description = "Train delayed", url = "https://x").display()
        assertEquals("Delay", d.header)
        assertEquals("Train delayed", d.description)
        assertEquals("https://x", d.url)
    }
}
