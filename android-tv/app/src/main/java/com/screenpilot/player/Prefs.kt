package com.screenpilot.player

import android.content.Context

/** Tiny wrapper around SharedPreferences — the only state the app keeps. */
object Prefs {

    // Name of the SharedPreferences file on disk (shared_prefs/screenpilot.xml).
    private const val FILE = "screenpilot"
    // The ONE key this app stores: the server's base address. Deliberately the
    // only native state — the screen's pairing identity/token lives inside the
    // WebView's localStorage, owned and managed by the web player itself. That
    // keeps this native shell stateless: point it at a server and the web side
    // handles pairing, identity and content on its own.
    private const val KEY_SERVER_URL = "serverUrl"

    /**
     * Reads the saved server address, or null when the app was never set up —
     * that null is what sends MainActivity to the setup screen. MODE_PRIVATE
     * means only this app can read the file.
     */
    fun serverUrl(context: Context): String? =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_SERVER_URL, null)

    /**
     * Saves the server address. trimEnd('/') is a second safety net (setup
     * already trims) so stored values never end in "/" and URL joining stays
     * clean. apply() writes to disk asynchronously in the background — the UI
     * never stalls waiting for the write.
     */
    fun setServerUrl(context: Context, url: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SERVER_URL, url.trimEnd('/'))
            .apply()
    }

    // Builds the full player URL from the saved base address, e.g.
    // "http://192.168.1.20:8080" -> "http://192.168.1.20:8080/player".
    // Returns null when nothing is configured, mirroring serverUrl() — that is
    // the signal MainActivity uses to redirect to the setup screen.
    /** The page the kiosk shows: <server>/player */
    fun playerUrl(context: Context): String? = serverUrl(context)?.let { "$it/player" }
}
