package dev.catchthenext.phone.liveupdate

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.core.net.toUri
import dev.catchthenext.android.tile.CachedDeparture
import dev.catchthenext.android.tile.clockTimeLabel
import dev.catchthenext.phone.MainActivity
import dev.catchthenext.phone.R

fun buildLiveUpdateNotification(
    ctx: Context,
    state: TrackingState,
    nextDepartures: List<CachedDeparture>,
    alertHeadline: String? = null,
    leaveByEpochMs: Long? = null,
): Notification {
    val nowMs = System.currentTimeMillis()
    val firstMinutes = nextDepartures.firstOrNull()?.let { (it.departureEpochMillis - nowMs) / 60_000 }
    val chipText = when {
        firstMinutes == null -> "—"
        firstMinutes <= 0 -> "Now"
        else -> "${firstMinutes}m"
    }

    val contentText = buildString {
        alertHeadline?.let { append(it) }
        nextDepartures.take(2).forEach { dep ->
            val m = (dep.departureEpochMillis - nowMs) / 60_000
            if (isNotEmpty()) append("\n")
            append(dep.routeShortName)
            if (dep.headsign.isNotBlank()) append(" → ${dep.headsign}")
            append(" ${if (m <= 0) "now" else "${m}m"}")
        }
        if (isEmpty()) append("No upcoming departures")
        // Only worth showing once there's meaningful slack — "leave by" a time in the past reads as broken.
        if (leaveByEpochMs != null && leaveByEpochMs > nowMs) {
            if (isNotEmpty()) append("\n")
            append("Leave by ${clockTimeLabel(leaveByEpochMs)}")
        }
    }

    val contentIntent = PendingIntent.getActivity(
        ctx, 0,
        Intent(Intent.ACTION_VIEW, "catchthenext://stop/${state.onestopId ?: state.stopId}".toUri())
            .setPackage(ctx.packageName),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    val stopIntent = PendingIntent.getBroadcast(
        ctx, 0,
        Intent(ctx, LiveUpdateActionReceiver::class.java).setAction(LiveUpdateActionReceiver.ACTION_STOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

    val startedAt = state.startedAt
    val firstEtaMs = state.firstDepartureEtaEpochMs
        ?: nextDepartures.firstOrNull()?.departureEpochMillis
    val totalSec = firstEtaMs?.let {
        ((it - startedAt) / 1_000).toInt().coerceAtLeast(1)
    } ?: 60
    val elapsedSec = ((nowMs - startedAt) / 1_000).toInt().coerceAtLeast(0)

    val busIcon = Icon.createWithResource(ctx, R.drawable.ic_directions_bus)

    val progressPct = if (totalSec > 0) (elapsedSec * 100 / totalSec).coerceIn(0, 100) else 0

    val progressStyle = Notification.ProgressStyle()
        .setProgress(progressPct)
        .setProgressTrackerIcon(busIcon)

    return Notification.Builder(ctx, LIVE_UPDATE_CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_directions_bus)
        .setContentTitle(state.stopName)
        .setContentText(contentText)
        .setShortCriticalText(chipText)
        .setContentIntent(contentIntent)
        .setCategory(Notification.CATEGORY_PROGRESS)
        .setStyle(progressStyle)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .addAction(
            Notification.Action.Builder(
                Icon.createWithResource(ctx, android.R.drawable.ic_menu_close_clear_cancel),
                "Stop",
                stopIntent,
            ).build()
        )
        .build()
}

/**
 * One-shot heads-up nudge posted once per tracking session when walk time eats into the
 * remaining slack before the tracked departure. Uses its own channel (with sound) so it
 * actually interrupts, unlike the silent ongoing tracking notification.
 */
fun buildLeaveNowNotification(ctx: Context, state: TrackingState, departureMinutes: Long): Notification {
    val contentIntent = PendingIntent.getActivity(
        ctx, 1,
        Intent(Intent.ACTION_VIEW, "catchthenext://stop/${state.onestopId ?: state.stopId}".toUri())
            .setPackage(ctx.packageName),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    return Notification.Builder(ctx, LEAVE_NOW_CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_directions_bus)
        .setContentTitle("Leave now for ${state.stopName}")
        .setContentText(
            if (departureMinutes <= 0) "Departure is due now"
            else "Departure in ${departureMinutes}m — time to walk"
        )
        .setContentIntent(contentIntent)
        .setCategory(Notification.CATEGORY_REMINDER)
        .setAutoCancel(true)
        .build()
}
