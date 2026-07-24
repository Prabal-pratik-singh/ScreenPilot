package com.screenpilot.player

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Kiosk shell around the ScreenPilot web player:
 *  - fullscreen, screen never sleeps, muted-video autoplay allowed
 *  - survives WebView renderer crashes (recreates itself)
 *  - auto-retries with backoff when the server/network is down
 *  - BACK is disabled; pressing BACK 5 times quickly opens the setup screen
 */
class MainActivity : Activity() {

    private lateinit var container: FrameLayout
    private var webView: WebView? = null
    private lateinit var errorView: View
    private lateinit var errorDetail: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var retryDelayMs = 5_000L
    private var pageFailed = false
    private var backPresses = mutableListOf<Long>()
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Prefs.playerUrl(this) == null) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        container = findViewById(R.id.webViewContainer)
        errorView = findViewById(R.id.errorView)
        errorDetail = findViewById(R.id.errorDetail)

        createWebView()
        watchNetwork()
    }

    // ------------------------------------------------------------ webview

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView() {
        val view = WebView(this)
        view.setBackgroundColor(0xFF000000.toInt())
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true          // localStorage: pairing token lives here
            databaseEnabled = true            // IndexedDB: offline media cache
            mediaPlaybackRequiresUserGesture = false // muted autoplay without a remote press
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        view.webViewClient = object : WebViewClient() {

            override fun onPageFinished(v: WebView?, url: String?) {
                if (!pageFailed) {
                    errorView.visibility = View.GONE
                    retryDelayMs = 5_000L
                }
                pageFailed = false
            }

            override fun onReceivedError(
                v: WebView?, request: WebResourceRequest?, error: WebResourceError?
            ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    && request?.isForMainFrame == true
                ) {
                    pageFailed = true
                    showErrorAndRetry("(${error?.errorCode ?: "?"}) ${error?.description ?: "network error"}")
                }
            }

            @Deprecated("pre-M callback")
            override fun onReceivedError(v: WebView?, code: Int, desc: String?, url: String?) {
                pageFailed = true
                showErrorAndRetry("($code) ${desc ?: "network error"}")
            }

            // WebView's renderer died (OOM on a low-end box) — rebuild everything
            override fun onRenderProcessGone(v: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                destroyWebView()
                handler.postDelayed({ createWebView() }, 1_000)
                return true
            }
        }
        container.addView(
            view,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        webView = view
        view.loadUrl(Prefs.playerUrl(this)!!)
    }

    private fun destroyWebView() {
        webView?.let {
            container.removeView(it)
            it.destroy()
        }
        webView = null
    }

    private fun showErrorAndRetry(detail: String) {
        errorDetail.text = detail
        errorView.visibility = View.VISIBLE
        handler.removeCallbacks(retryRunnable)
        handler.postDelayed(retryRunnable, retryDelayMs)
        retryDelayMs = (retryDelayMs * 2).coerceAtMost(60_000L)
    }

    private val retryRunnable = Runnable {
        webView?.loadUrl(Prefs.playerUrl(this) ?: return@Runnable)
    }

    // ------------------------------------------------------------ network watchdog

    private fun watchNetwork() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // network is back — reload immediately instead of waiting out the backoff
                handler.post {
                    if (errorView.visibility == View.VISIBLE) {
                        handler.removeCallbacks(retryRunnable)
                        retryRunnable.run()
                    }
                }
            }
        }
        cm.registerDefaultNetworkCallback(callback)
        networkCallback = callback
    }

    // ------------------------------------------------------------ kiosk keys

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            val now = System.currentTimeMillis()
            backPresses.add(now)
            backPresses = backPresses.filter { now - it < 3_000 }.toMutableList()
            if (backPresses.size >= 5) {
                backPresses.clear()
                startActivity(Intent(this, SetupActivity::class.java))
            }
            return true // swallow BACK: a kiosk must not be exit-able by accident
        }
        return super.onKeyDown(keyCode, event)
    }

    // ------------------------------------------------------------ fullscreen

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }

    // ------------------------------------------------------------ lifecycle

    override fun onResume() {
        super.onResume()
        webView?.onResume()
        // returning from setup with a changed URL? reload
        val url = Prefs.playerUrl(this)
        if (url != null && webView?.url?.startsWith(Prefs.serverUrl(this) ?: "") == false) {
            webView?.loadUrl(url)
        }
    }

    override fun onPause() {
        // deliberately NOT calling webView.onPause(): signage keeps playing
        super.onPause()
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            networkCallback?.let {
                (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
                    .unregisterNetworkCallback(it)
            }
        }
        handler.removeCallbacksAndMessages(null)
        destroyWebView()
        super.onDestroy()
    }
}
