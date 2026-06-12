package dev.catchthenext.android.ui

import dev.catchthenext.model.Alert
import dev.catchthenext.model.AlertSeverity

/**
 * Platform-agnostic emphasis derived from an [Alert]'s severity. The phone and wear alert
 * composables map this to their own colors so the severity-to-emphasis policy lives in one place.
 */
enum class AlertEmphasis { CRITICAL, ELEVATED, NORMAL }

fun Alert.emphasis(): AlertEmphasis = when (severityLevel) {
    AlertSeverity.SEVERE -> AlertEmphasis.CRITICAL
    AlertSeverity.WARNING -> AlertEmphasis.ELEVATED
    AlertSeverity.INFO, AlertSeverity.UNKNOWN_SEVERITY -> AlertEmphasis.NORMAL
}

/** The user-facing fields of an [Alert], normalized for display. */
data class AlertDisplay(
    val header: String,
    val description: String?,
    val url: String?,
)

fun Alert.display(): AlertDisplay = AlertDisplay(
    header = headerText.orEmpty(),
    description = descriptionText?.takeIf { it.isNotBlank() },
    url = url?.takeIf { it.isNotBlank() },
)
