package dev.catchthenext.phone.liveupdate

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

const val LIVE_UPDATE_CHANNEL_ID = "live_updates_v1"
const val LIVE_UPDATE_NOTIF_ID = 1001

fun ensureLiveUpdateChannel(ctx: Context) {
    val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (nm.getNotificationChannel(LIVE_UPDATE_CHANNEL_ID) != null) return
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
