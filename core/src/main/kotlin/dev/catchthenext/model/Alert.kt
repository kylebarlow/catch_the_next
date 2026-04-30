package dev.catchthenext.model

enum class AlertSeverity { UNKNOWN_SEVERITY, INFO, WARNING, SEVERE }

data class AlertActivePeriod(
    val startEpochSeconds: Long?,
    val endEpochSeconds: Long?,
)

data class Alert(
    val cause: String?,
    val effect: String?,
    val severityLevel: AlertSeverity = AlertSeverity.UNKNOWN_SEVERITY,
    val headerText: String?,
    val descriptionText: String?,
    val ttsHeaderText: String? = null,
    val ttsDescriptionText: String? = null,
    val url: String? = null,
    val activePeriod: List<AlertActivePeriod> = emptyList(),
)
