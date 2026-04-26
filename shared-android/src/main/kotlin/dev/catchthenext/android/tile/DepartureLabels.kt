package dev.catchthenext.android.tile

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun timeLabel(minutes: Long): String = if (minutes <= 0L) "Now" else "${minutes}m"

fun freshnessLabel(fetchedAt: Long, now: Long = System.currentTimeMillis()): String {
    val ageMinutes = (now - fetchedAt) / 60_000
    return if (ageMinutes < 1) "Live" else "${ageMinutes}m ago"
}

fun updatedAtLabel(fetchedAt: Long): String {
    val fmt = SimpleDateFormat("h:mma", Locale.US)
    return "Updated ${fmt.format(Date(fetchedAt)).lowercase()}"
}
