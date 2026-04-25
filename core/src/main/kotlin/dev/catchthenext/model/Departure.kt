package dev.catchthenext.model

data class Departure(
    val stopId: Long,
    val departureTime: String,
    val departureMinutes: Long,
    val routeShortName: String,
    val routeLongName: String,
    val headsign: String,
    val scheduleRelationship: String = "SCHEDULED",
    val agencyName: String? = null,
    val feed: FeedAttribution? = null
) {
    fun displayString(): String {
        val timeLabel = when {
            departureMinutes <= 0 -> "Now    "
            departureMinutes == 1L -> "1 min  "
            else -> "${departureMinutes} mins "
        }.padEnd(8)

        val routeLabel = when {
            routeShortName.isNotBlank() -> "Route ${routeShortName.trim()}"
            routeLongName.isNotBlank() -> routeLongName.take(30)
            else -> "Unknown route"
        }

        val headsignLabel = if (headsign.isNotBlank()) " → $headsign" else ""
        val rtLabel = if (scheduleRelationship != "SCHEDULED") " [$scheduleRelationship]" else ""

        return "  $timeLabel | $routeLabel$headsignLabel$rtLabel"
    }
}
