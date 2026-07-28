package com.screenpilot.player

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * One-field setup: the ScreenPilot server address. D-pad friendly —
 * remote users tab between the field and the two buttons.
 */
class SetupActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        // The entire UI is just three controls: one text field for the server
        // address plus Test and Save buttons. Keeping it to a single field is
        // what makes the screen comfortably usable with a TV remote's D-pad —
        // no scrolling through many inputs with arrow keys.
        val urlField = findViewById<EditText>(R.id.serverUrl)
        val saveButton = findViewById<Button>(R.id.saveButton)
        val testButton = findViewById<Button>(R.id.testButton)

        // Pre-fill the previously saved address (if any) so a technician who is
        // only checking or tweaking it does not have to retype the whole URL.
        Prefs.serverUrl(this)?.let { urlField.setText(it) }

        // "Test connection": checks that the typed address really reaches a live
        // ScreenPilot server before the user commits to saving it.
        testButton.setOnClickListener {
            // Validate and clean the input first; on a bad address show the
            // explanatory toast and stop right here.
            val url = normalize(urlField.text.toString()) ?: return@setOnClickListener toastInvalid()
            // Disable the button while the check runs, so impatient extra
            // presses cannot start several overlapping probes.
            testButton.isEnabled = false
            // Android forbids network calls on the UI thread (they would freeze
            // the screen, and the OS crashes the app with
            // NetworkOnMainThreadException), so the probe runs on a plain
            // background thread.
            thread {
                val message = try {
                    // GET <server>/api/health — the backend's health endpoint. The
                    // server answers it only after a database round-trip, so a 200
                    // proves the WHOLE stack works (network path, backend app AND
                    // its DB), not merely that something is listening on the port.
                    val conn = URL("$url/api/health").openConnection() as HttpURLConnection
                    // 5s to connect + 5s to read: a wrong IP or a sleeping server
                    // fails fast instead of leaving the button dead for minutes.
                    conn.connectTimeout = 5000
                    conn.readTimeout = 5000
                    // Reading responseCode is what actually sends the request.
                    val code = conn.responseCode
                    if (code == 200) "Server reachable ✓" else "Server answered HTTP $code"
                } catch (e: Exception) {
                    // DNS failure, connection refused, timeout — all land here.
                    "Cannot reach server: ${e.message}"
                }
                // Hop back to the UI thread: buttons and toasts may only be
                // touched from there.
                runOnUiThread {
                    testButton.isEnabled = true
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                }
            }
        }

        // "Save": persist the address and hand control over to the player.
        saveButton.setOnClickListener {
            // Same validation as Test — a malformed address is never saved.
            val url = normalize(urlField.text.toString()) ?: return@setOnClickListener toastInvalid()
            Prefs.setServerUrl(this, url)
            // Relaunch MainActivity so it loads from the (possibly new) server,
            // and finish() so BACK cannot land on this setup screen again.
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }

    /**
     * Cleans and validates the typed address. Trims surrounding whitespace and
     * any trailing "/" so paths like "/player" or "/api/health" can be appended
     * later without producing a double slash. Requires an explicit http:// or
     * https:// scheme; anything else returns null — the signal that makes the
     * callers show the "invalid" toast instead of proceeding.
     */
    private fun normalize(raw: String): String? {
        val trimmed = raw.trim().trimEnd('/')
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else null
    }

    /** Small popup explaining the one validation rule the address must satisfy. */
    private fun toastInvalid() {
        Toast.makeText(this, "Address must start with http:// or https://", Toast.LENGTH_LONG).show()
    }
}
