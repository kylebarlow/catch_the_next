package dev.catchthenext.phone.liveupdate

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.catchthenext.phone.PhoneGraph

class LiveUpdateActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_STOP = "dev.catchthenext.phone.ACTION_STOP_LIVE_UPDATE"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_STOP) {
            PhoneGraph.liveUpdateController(context).stop()
        }
    }
}
