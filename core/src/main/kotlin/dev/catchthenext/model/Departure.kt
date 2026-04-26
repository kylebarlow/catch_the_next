package dev.catchthenext.model

enum class DepartureTimeSource { LIVE, SCHEDULED }

data class Departure(
    val stopId: Long,
    val scheduledDepartureTime: String?,
    val scheduledDepartureMinutes: Long?,
    val liveDepartureTime: String?,
    val liveDepartureMinutes: Long?,
    val displayDepartureTime: String,
    val displayDepartureMinutes: Long,
    val timeSource: DepartureTimeSource,
    val routeShortName: String,
    val routeLongName: String,
    val headsign: String,
    val scheduleRelationship: String = "SCHEDULED",
    val agencyName: String? = null,
    val feed: FeedAttribution? = null
) {
    fun displayString(): String {
        val timeLabel = when {
            displayDepartureMinutes <= 0 -> "Now    "
            displayDepartureMinutes == 1L -> "1 min  "
            else -> "${displayDepartureMinutes} mins "
        }.padEnd(8)

        val routeLabel = when {
            routeShortName.isNotBlank() -> "Route ${routeShortName.trim()}"
            routeLongName.isNotBlank() -> routeLongName.take(30)
            else -> "Unknown route"
        }

        val headsignLabel = if (headsign.isNotBlank()) " → $headsign" else ""
        val rtLabel = if (scheduleRelationship != "SCHEDULED") " [$scheduleRelationship]" else ""
        val srcLabel = if (timeSource == DepartureTimeSource.LIVE) " LIVE" else ""

        return "  $timeLabel | $routeLabel$headsignLabel$rtLabel$srcLabel"
    }
}
