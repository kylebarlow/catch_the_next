package dev.catchthenext.model

data class Stop(
    val id: Long,
    val stopId: String,
    val stopName: String,
    val lat: Double,
    val lon: Double,
    val onestopId: String? = null,
    val feed: FeedAttribution? = null
) {
    fun displayString(): String = "$stopName  [ID: $id | GTFS: $stopId]"
}
