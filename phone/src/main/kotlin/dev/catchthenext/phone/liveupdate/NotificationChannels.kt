package dev.catchthenext.phone.liveupdate

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

const val LIVE_UPDATE_CHANNEL_ID = "live_updates_v1"
const val LIVE_UPDATE_NOTIF_ID = 1001

// Separate channel (own sound/vibration) for the one-shot "leave now" nudge — the ongoing
// tracking notification stays silent so it doesn't re-alert on every 30s refresh.
const val LEAVE_NOW_CHANNEL_ID = "leave_now_v1"
const val LEAVE_NOW_NOTIF_ID = 1002

fun ensureLiveUpdateChannel(ctx: Context) {
    val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (nm.getNotificationChannel(LIVE_UPDATE_CHANNEL_ID) == null) {
        val channel = NotificationChannel(
            LIVE_UPDATE_CHANNEL_ID,
            "Live transit updates",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Shows next departure countdown while heading to a stop"
            setSound(null, null)
            enableVibration(false)
        }
        nm.createNotificationChannel(channel)
    }
    if (nm.getNotificationChannel(LEAVE_NOW_CHANNEL_ID) == null) {
        val channel = NotificationChannel(
            LEAVE_NOW_CHANNEL_ID,
            "Leave now alerts",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "One-time heads-up alert when it's time to leave for your stop"
        }
        nm.createNotificationChannel(channel)
    }
}
