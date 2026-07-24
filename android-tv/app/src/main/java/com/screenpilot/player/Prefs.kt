package com.screenpilot.player

import android.content.Context

/** Tiny wrapper around SharedPreferences — the only state the app keeps. */
object Prefs {

    private const val FILE = "screenpilot"
    private const val KEY_SERVER_URL = "serverUrl"

    fun serverUrl(context: Context): String? =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_SERVER_URL, null)

    fun setServerUrl(context: Context, url: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SERVER_URL, url.trimEnd('/'))
            .apply()
    }

    /** The page the kiosk shows: <server>/player */
    fun playerUrl(context: Context): String? = serverUrl(context)?.let { "$it/player" }
}
