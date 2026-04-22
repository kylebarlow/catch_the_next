package dev.catchthenext.model

data class Stop(
    val id: Int,
    val stopId: String,
    val stopName: String,
    val lat: Double,
    val lon: Double,
    val onestopId: String? = null
) {
    fun displayString(): String = "$stopName  [ID: $id | GTFS: $stopId]"
}
