package dev.catchthenext.wear.tile

fun timeLabel(minutes: Long): String = if (minutes <= 0L) "Now" else "${minutes}m"

fun freshnessLabel(fetchedAt: Long, now: Long = System.currentTimeMillis()): String {
    val ageMinutes = (now - fetchedAt) / 60_000
    return if (ageMinutes < 1) "Live" else "${ageMinutes}m ago"
}
