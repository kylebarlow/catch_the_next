package dev.catchthenext.android.tile

import dev.catchthenext.model.DepartureTimeSource
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val liveDepartureColorArgb: Int = 0xFF4CAF50.toInt()
val scheduledDepartureColorArgb: Int = 0xFFFFA726.toInt()

fun departureColorArgb(timeSource: DepartureTimeSource): Int =
    if (timeSource == DepartureTimeSource.LIVE) liveDepartureColorArgb else scheduledDepartureColorArgb

fun timeLabel(minutes: Long): String = if (minutes <= 0L) "Now" else "${minutes}m"

fun freshnessLabel(fetchedAt: Long, now: Long = System.currentTimeMillis()): String {
    val ageMinutes = (now - fetchedAt) / 60_000
    return if (ageMinutes < 1) "Just updated" else "${ageMinutes}m ago"
}

fun updatedAtLabel(fetchedAt: Long): String {
    val fmt = SimpleDateFormat("h:mma", Locale.US)
    return "Updated ${fmt.format(Date(fetchedAt)).lowercase()}"
}
