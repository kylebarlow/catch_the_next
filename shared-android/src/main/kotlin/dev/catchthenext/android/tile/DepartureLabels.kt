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

/**
 * Parses a GTFS "HH:mm:ss" departure time (hours may exceed 24 for after-midnight service)
 * into a 12-hour clock label like "3:42 PM". Returns null if the string can't be parsed.
 */
fun absoluteTimeLabel(gtfsTime: String?): String? {
    if (gtfsTime.isNullOrBlank()) return null
    val parts = gtfsTime.split(":")
    if (parts.size < 2) return null
    val hour24 = parts[0].toIntOrNull() ?: return null
    val minute = parts[1].toIntOrNull() ?: return null
    val h = ((hour24 % 24) + 24) % 24
    val ampm = if (h < 12) "AM" else "PM"
    val hour12 = when (h % 12) { 0 -> 12; else -> h % 12 }
    return "%d:%02d %s".format(hour12, minute, ampm)
}

/** Clock label ("3:42 PM") for an epoch-millis instant, in the device's default time zone. */
fun clockTimeLabel(epochMillis: Long): String =
    SimpleDateFormat("h:mm a", Locale.US).format(Date(epochMillis))

fun freshnessLabel(fetchedAt: Long, now: Long = System.currentTimeMillis()): String {
    val ageMinutes = (now - fetchedAt) / 60_000
    return if (ageMinutes < 1) "Just updated" else "${ageMinutes}m ago"
}

fun updatedAtLabel(fetchedAt: Long): String {
    val fmt = SimpleDateFormat("h:mma", Locale.US)
    return "Updated ${fmt.format(Date(fetchedAt)).lowercase()}"
}
