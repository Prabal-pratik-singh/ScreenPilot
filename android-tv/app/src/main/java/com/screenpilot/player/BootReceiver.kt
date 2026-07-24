package com.screenpilot.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Starts the player when the TV powers on — the box becomes an appliance:
 * plug in, and signage plays. Only fires once a server has been configured.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != "android.intent.action.QUICKBOOT_POWERON") {
            return
        }
        if (Prefs.serverUrl(context) == null) return
        val launch = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launch)
    }
}
