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

    // The FrameLayout from activity_main.xml that hosts the WebView. The WebView
    // itself is NOT declared in the XML: it is built in code (createWebView) so
    // that after a renderer crash we can rip the dead view out of this container
    // and insert a brand-new one — impossible with an XML-baked WebView.
    private lateinit var container: FrameLayout
    // Nullable on purpose: between "destroyed after a crash" and "rebuilt 1s later"
    // there genuinely is no WebView, and null makes that gap explicit and safe.
    private var webView: WebView? = null
    // Fullscreen overlay (defined in the XML) shown when the player page cannot load.
    private lateinit var errorView: View
    // The line of text inside the overlay showing the actual error code/message.
    private lateinit var errorDetail: TextView

    // Handler bound to the main (UI) thread: schedules delayed work (retry timers,
    // the 1s WebView rebuild) and lets background callbacks hop onto the UI thread,
    // because Android only allows touching views from that one thread.
    private val handler = Handler(Looper.getMainLooper())
    // Current wait before the next reload attempt. Starts at 5s and doubles after
    // each failure (exponential backoff) so a dead server is not hammered.
    private var retryDelayMs = 5_000L
    // Set by the error callbacks so onPageFinished can tell a real, clean load
    // apart from "finished rendering the failure page" — only a clean load may
    // hide the error overlay and reset the backoff.
    private var pageFailed = false
    // Timestamps (in ms) of recent BACK presses — the sliding 3-second window
    // behind the hidden "press BACK 5 times to open setup" service door.
    private var backPresses = mutableListOf<Long>()
    // Kept so onDestroy can unregister the connectivity listener; forgetting to
    // unregister would leak this Activity and keep firing callbacks after death.
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // First run: no server address saved yet, so there is nothing to play.
        // Send the user to the setup screen instead, and finish() this activity
        // so it is not left underneath on the back stack. Setup relaunches
        // MainActivity once an address has been saved.
        if (Prefs.playerUrl(this) == null) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }

        // Ask Android to keep the screen awake for as long as this window is
        // visible — vital for signage; without it the TV would blank or sleep
        // after the normal screen timeout.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Inflate the XML layout — it contains only the empty container and the
        // error overlay; the WebView itself is added in code right below.
        setContentView(R.layout.activity_main)
        // Look up the three views by their XML ids so the code can drive them.
        container = findViewById(R.id.webViewContainer)
        errorView = findViewById(R.id.errorView)
        errorDetail = findViewById(R.id.errorDetail)

        // Build the WebView and start loading the player page...
        createWebView()
        // ...and start listening for "connectivity is back" so outages recover fast.
        watchNetwork()
    }

    // ------------------------------------------------------------ webview

    /**
     * Builds the WebView entirely in code rather than in the XML layout. Why:
     * when the WebView's renderer process crashes (see onRenderProcessGone) the
     * old view object is permanently broken — the only cure is to remove it and
     * construct a fresh one. Creating it programmatically makes that
     * destroy-and-rebuild cycle possible.
     *
     * The @SuppressLint silences the "enabling JavaScript can allow XSS" lint
     * warning — here JavaScript is the whole point: the player IS a JS app,
     * loaded only from the server the owner configured.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView() {
        val view = WebView(this)
        // Paint the WebView black so loading gaps show as black (invisible on a
        // dark signage screen) instead of a glaring white flash.
        view.setBackgroundColor(0xFF000000.toInt())
        view.settings.apply {
            // The web player is a JavaScript (React) app — without this nothing renders.
            javaScriptEnabled = true
            // Allows window.localStorage. The player stores its pairing token (this
            // screen's identity) there, so the TV stays paired across restarts.
            domStorageEnabled = true          // localStorage: pairing token lives here
            // Allows IndexedDB, the in-browser database the player uses to cache
            // downloaded images/videos so playback survives network outages.
            databaseEnabled = true            // IndexedDB: offline media cache
            // Browsers normally refuse to start audio/video until a human interacts
            // with the page. A signage TV has no human tapping it, so lift that rule
            // and let muted videos autoplay on their own.
            mediaPlaybackRequiresUserGesture = false // muted autoplay without a remote press
            // Standard caching: reuse valid cached resources, hit the network otherwise.
            cacheMode = WebSettings.LOAD_DEFAULT
            // If the portal page is https but some media arrives over plain http
            // ("mixed content"), COMPATIBILITY_MODE still loads it instead of
            // silently blocking it — common with LAN servers and tunnel setups.
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        // The WebViewClient is our hook into page-load events: success, load
        // errors, and renderer crashes all arrive through the overrides below.
        view.webViewClient = object : WebViewClient() {

            // Fires when ANY page load ends — including a failed one that just
            // rendered an error page. The pageFailed flag tells the cases apart.
            override fun onPageFinished(v: WebView?, url: String?) {
                // Clean load: hide the error overlay and reset the retry delay to
                // its 5s starting value so the NEXT outage begins with fast retries.
                if (!pageFailed) {
                    errorView.visibility = View.GONE
                    retryDelayMs = 5_000L
                }
                // Consume the flag so it only ever describes the load that just ended.
                pageFailed = false
            }

            // Modern error callback (Android 6.0 / API 23+). Unlike the old one
            // below, it fires for EVERY failed request — each image, script or
            // favicon — so it must be filtered to the main frame: one broken
            // thumbnail must not flip the whole player onto the error screen.
            override fun onReceivedError(
                v: WebView?, request: WebResourceRequest?, error: WebResourceError?
            ) {
                // SDK check: the request/error details used here only exist reliably
                // from API 23 up (the app supports down to minSdk 21).
                // isForMainFrame == true means the player page ITSELF failed to
                // load, not some sub-resource inside it.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    && request?.isForMainFrame == true
                ) {
                    // Mark the failure (so onPageFinished keeps the overlay up) and
                    // show the error screen with a scheduled, backed-off retry.
                    pageFailed = true
                    showErrorAndRetry("(${error?.errorCode ?: "?"}) ${error?.description ?: "network error"}")
                }
            }

            // Fallback error callback for Android 5.x (API 21-22), where the modern
            // overload above never fires. On those old versions this variant is only
            // called for the main page anyway, so no frame filtering is needed.
            @Deprecated("pre-M callback")
            override fun onReceivedError(v: WebView?, code: Int, desc: String?, url: String?) {
                pageFailed = true
                showErrorAndRetry("($code) ${desc ?: "network error"}")
            }

            // The renderer is the separate OS process that actually draws the page.
            // Cheap TV boxes kill it under memory pressure; once it is gone this
            // WebView object is dead forever (it would only show a frozen frame).
            // WebView's renderer died (OOM on a low-end box) — rebuild everything
            override fun onRenderProcessGone(v: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                // Throw away the corpse: detach the dead view and free its resources...
                destroyWebView()
                // ...then build a fresh WebView after a 1-second pause, giving the
                // system a moment to reclaim the memory that likely caused the crash.
                handler.postDelayed({ createWebView() }, 1_000)
                // Returning true tells Android we handled the renderer crash ourselves,
                // so the OS does not kill the whole app (returning false would
                // terminate the entire process).
                return true
            }
        }
        // Insert the new WebView into the container, stretched to fill the whole
        // screen (MATCH_PARENT in both width and height).
        container.addView(
            view,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        // Remember the live instance, then start loading the player page. The !!
        // is safe: onCreate already bounced to setup when no URL was configured.
        webView = view
        view.loadUrl(Prefs.playerUrl(this)!!)
    }

    /**
     * Fully disposes of the current WebView (if one exists). Used both for
     * renderer-crash recovery and for the final cleanup in onDestroy.
     */
    private fun destroyWebView() {
        webView?.let {
            // A WebView must be detached from its parent view before destroy(),
            // otherwise Android complains and can crash.
            container.removeView(it)
            // Releases the renderer, the JavaScript engine and all page resources.
            it.destroy()
        }
        // null = the explicit "no WebView exists right now" state.
        webView = null
    }

    /**
     * Shows the error overlay and schedules a reload with exponential backoff:
     * the wait doubles on every consecutive failure (5s -> 10s -> 20s -> 40s ->
     * capped at 60s), so a server that is down for hours is not hammered, yet a
     * short blip recovers within seconds. onPageFinished resets the delay back
     * to 5s after the first clean load.
     */
    private fun showErrorAndRetry(detail: String) {
        // Put the human-readable failure reason on screen for the technician.
        errorDetail.text = detail
        errorView.visibility = View.VISIBLE
        // Cancel any retry that is already queued, so repeated failures never
        // stack up multiple simultaneous reload attempts...
        handler.removeCallbacks(retryRunnable)
        // ...then queue exactly one reload after the current backoff delay.
        handler.postDelayed(retryRunnable, retryDelayMs)
        // Double the wait for next time, but never beyond 60 seconds.
        retryDelayMs = (retryDelayMs * 2).coerceAtMost(60_000L)
    }

    // The queued "try again" action: simply asks the existing WebView to load the
    // player URL once more. If the URL vanished in the meantime (server unset),
    // it silently does nothing this round instead of crashing.
    private val retryRunnable = Runnable {
        webView?.loadUrl(Prefs.playerUrl(this) ?: return@Runnable)
    }

    // ------------------------------------------------------------ network watchdog

    /**
     * Registers a listener that fires the moment the box has a working network
     * again (ethernet re-plugged, Wi-Fi reconnects, router rebooted). Without it
     * the player could sit on the error screen for up to a full 60s backoff even
     * though connectivity already returned — with it, recovery is immediate.
     */
    private fun watchNetwork() {
        // registerDefaultNetworkCallback() only exists on Android 7.0 (API 24)+.
        // On older devices we simply skip this shortcut — the timed retry loop
        // still recovers, just more slowly.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // network is back — reload immediately instead of waiting out the backoff
                // This callback arrives on a background thread; handler.post hops
                // over to the UI thread, the only place views may be touched.
                handler.post {
                    // Only intervene when the error screen is actually showing —
                    // a healthy playing session must not be interrupted.
                    if (errorView.visibility == View.VISIBLE) {
                        // Cancel the pending backoff timer and retry right now.
                        handler.removeCallbacks(retryRunnable)
                        retryRunnable.run()
                    }
                }
            }
        }
        // "Default network" = whichever network the device currently routes its
        // traffic through; onAvailable fires whenever one becomes usable.
        cm.registerDefaultNetworkCallback(callback)
        // Keep the reference so onDestroy can unregister it later.
        networkCallback = callback
    }

    // ------------------------------------------------------------ kiosk keys

    /**
     * Intercepts remote-control keys. BACK gets special kiosk treatment: it is
     * ALWAYS swallowed (a passer-by with a remote must never be able to close
     * the signage), but pressing it 5 times within 3 seconds is the hidden
     * "service door" that opens the setup screen for a technician.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            val now = System.currentTimeMillis()
            // Record this press, then drop any presses older than 3 seconds —
            // a sliding window, so 5 slow presses spread over a minute never trigger.
            backPresses.add(now)
            backPresses = backPresses.filter { now - it < 3_000 }.toMutableList()
            // 5 presses still inside the window = deliberate. Clear the list so
            // the door doesn't instantly re-trigger, and open the setup screen.
            if (backPresses.size >= 5) {
                backPresses.clear()
                startActivity(Intent(this, SetupActivity::class.java))
            }
            // Returning true means "this key is handled": Android skips its
            // default BACK behaviour, which would have closed the player.
            return true // swallow BACK: a kiosk must not be exit-able by accident
        }
        // Any other key (d-pad, volume, ...) keeps its normal system behaviour.
        return super.onKeyDown(keyCode, event)
    }

    // ------------------------------------------------------------ fullscreen

    /**
     * Re-applies fullscreen every time this window regains focus. Android
     * quietly brings the system bars back after dialogs, toasts or HDMI events —
     * re-hiding on every focus gain keeps the kiosk permanently edge-to-edge.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    /**
     * Hides the status and navigation bars ("immersive" fullscreen). This uses
     * the old systemUiVisibility API on purpose: the modern replacement
     * (WindowInsetsController) only exists on Android 11+, while this app must
     * run down to minSdk 21 — the deprecated flags work everywhere, hence the
     * @Suppress instead of a migration.
     */
    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        // IMMERSIVE_STICKY: bars stay hidden and auto-re-hide after a swipe;
        // FULLSCREEN + HIDE_NAVIGATION: hide the status bar and the nav bar;
        // the three LAYOUT_* flags: lay the page out as if the bars were never
        // there, so the content doesn't jump when they briefly appear.
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }

    // ------------------------------------------------------------ lifecycle

    /**
     * Runs every time the activity returns to the foreground (app start, and
     * coming back from the setup screen).
     */
    override fun onResume() {
        super.onResume()
        // Make sure WebView timers/JS/video are running (the counterpart of the
        // webView.onPause() call that we deliberately never make below).
        webView?.onResume()
        // returning from setup with a changed URL? reload
        // If the page currently loaded does not start with the saved server
        // address, the user changed servers in setup — load the new player URL.
        val url = Prefs.playerUrl(this)
        if (url != null && webView?.url?.startsWith(Prefs.serverUrl(this) ?: "") == false) {
            webView?.loadUrl(url)
        }
    }

    /**
     * A normal app would call webView.onPause() here to halt JS, timers and
     * video while backgrounded. This kiosk skips that on purpose: brief focus
     * losses (system popups, HDMI-CEC noise) must not freeze the signage.
     */
    override fun onPause() {
        // deliberately NOT calling webView.onPause(): signage keeps playing
        super.onPause()
    }

    /**
     * Final cleanup when Android tears the activity down. Everything registered
     * or scheduled above is undone here so nothing fires into a dead activity
     * or leaks memory.
     */
    override fun onDestroy() {
        // Same API 24+ guard as watchNetwork(): the callback can only have been
        // registered there, so only then is there anything to unregister.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            networkCallback?.let {
                (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
                    .unregisterNetworkCallback(it)
            }
        }
        // Drop EVERY pending handler task (retry timers, the delayed WebView
        // rebuild) in one sweep — passing null means "remove all".
        handler.removeCallbacksAndMessages(null)
        // Release the WebView and all its resources.
        destroyWebView()
        super.onDestroy()
    }
}
