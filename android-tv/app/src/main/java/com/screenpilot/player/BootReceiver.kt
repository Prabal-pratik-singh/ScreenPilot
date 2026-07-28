package com.screenpilot.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Starts the player when the TV powers on — the box becomes an appliance:
 * plug in, and signage plays. Only fires once a server has been configured.
 */
class BootReceiver : BroadcastReceiver() {

    /**
     * Android calls this when one of the broadcasts declared in the manifest
     * arrives — here, the "device finished booting" announcements.
     */
    override fun onReceive(context: Context, intent: Intent) {
        // A broadcast with no action string tells us nothing — ignore it.
        val action = intent.action ?: return
        // React to the standard BOOT_COMPLETED, and ALSO to QUICKBOOT_POWERON —
        // a vendor variant some OEM boxes send INSTEAD of the standard one when
        // they wake from their "fake off" quick-boot state. Listening for both
        // makes autostart work on more TV hardware.
        if (action != Intent.ACTION_BOOT_COMPLETED && action != "android.intent.action.QUICKBOOT_POWERON") {
            return
        }
        // No server configured yet (fresh install, never set up): stay silent.
        // Auto-opening a screen on every boot of an unconfigured box would be
        // annoying and pointless — there is nothing to play.
        if (Prefs.serverUrl(context) == null) return
        // FLAG_ACTIVITY_NEW_TASK is mandatory here: a BroadcastReceiver is not
        // an activity, so it has no task (window stack) for the new screen to
        // join — without this flag Android rejects startActivity() from a
        // receiver context and throws instead of launching.
        val launch = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launch)
    }
}
