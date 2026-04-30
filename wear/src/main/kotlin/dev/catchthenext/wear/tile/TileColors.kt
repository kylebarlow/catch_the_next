package dev.catchthenext.wear.tile

import dev.catchthenext.model.DepartureTimeSource

internal object TileColors {
    val accent: Int = 0xFF8AE6FF.toInt()
    val textPrimary: Int = 0xFFFFFFFF.toInt()
    val textDim: Int = 0xFFB0B0B0.toInt()
    val liveDeparture: Int = 0xFF4CAF50.toInt()
    val scheduledDeparture: Int = 0xFFFFA726.toInt()
    val warning: Int = 0xFFFFC107.toInt()

    fun departureColor(timeSource: DepartureTimeSource): Int =
        if (timeSource == DepartureTimeSource.LIVE) liveDeparture else scheduledDeparture
}
