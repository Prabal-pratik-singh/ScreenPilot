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

        val urlField = findViewById<EditText>(R.id.serverUrl)
        val saveButton = findViewById<Button>(R.id.saveButton)
        val testButton = findViewById<Button>(R.id.testButton)

        Prefs.serverUrl(this)?.let { urlField.setText(it) }

        testButton.setOnClickListener {
            val url = normalize(urlField.text.toString()) ?: return@setOnClickListener toastInvalid()
            testButton.isEnabled = false
            thread {
                val message = try {
                    val conn = URL("$url/api/health").openConnection() as HttpURLConnection
                    conn.connectTimeout = 5000
                    conn.readTimeout = 5000
                    val code = conn.responseCode
                    if (code == 200) "Server reachable ✓" else "Server answered HTTP $code"
                } catch (e: Exception) {
                    "Cannot reach server: ${e.message}"
                }
                runOnUiThread {
                    testButton.isEnabled = true
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                }
            }
        }

        saveButton.setOnClickListener {
            val url = normalize(urlField.text.toString()) ?: return@setOnClickListener toastInvalid()
            Prefs.setServerUrl(this, url)
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }

    private fun normalize(raw: String): String? {
        val trimmed = raw.trim().trimEnd('/')
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else null
    }

    private fun toastInvalid() {
        Toast.makeText(this, "Address must start with http:// or https://", Toast.LENGTH_LONG).show()
    }
}
