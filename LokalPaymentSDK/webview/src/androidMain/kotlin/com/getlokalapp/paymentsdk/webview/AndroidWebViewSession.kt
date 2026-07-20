package com.getlokalapp.paymentsdk.webview

import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.getlokalapp.paymentsdk.hostcontext.ActivityTracker

actual fun createWebViewSession(config: WebViewConfig): WebViewSession = AndroidWebViewSession(config)

/**
 * Handoff for the single in-flight WebView launch. The [WebViewConfig] and its
 * handlers/listener can't ride along in the launch Intent (lambdas aren't
 * Parcelable), so the session parks itself here for [WebViewActivity] to pick
 * up — same pattern as `:razorpay-checkout`'s RazorpayCheckoutBridge. One
 * WebView at a time, so a single slot suffices.
 */
internal object WebViewLaunchBridge {
    @Volatile
    var pending: AndroidWebViewSession? = null
}

/**
 * Drives a WebView on Android by launching [WebViewActivity], an internal proxy
 * that owns the `android.webkit.WebView`. The Activity to launch from comes from
 * [ActivityTracker] (`:shared`'s hostcontext utility) at call time, not a
 * host-supplied handle. Once the Activity is up it binds itself back via
 * [activity] so [evaluateJavascript] / [close] can reach the live WebView.
 */
internal class AndroidWebViewSession(val config: WebViewConfig) : WebViewSession {

    // Set by WebViewActivity.onCreate, cleared on its onDestroy. Read/written
    // from the main thread only (launch, bind, teardown all happen there).
    @Volatile
    var activity: WebViewActivity? = null

    private val main = Handler(Looper.getMainLooper())

    override fun load(request: WebViewRequest) {
        val bound = activity
        if (bound != null) {
            main.post { bound.loadRequest(request) }
            return
        }
        val host = ActivityTracker.current
        if (host == null) {
            config.listener?.onError(ERROR_NO_ACTIVITY, "webview_no_activity")
            return
        }
        WebViewLaunchBridge.pending = this
        pendingRequest = request
        host.startActivity(Intent(host, WebViewActivity::class.java))
    }

    override fun evaluateJavascript(script: String, onResult: ((String?) -> Unit)?) {
        val bound = activity
        main.post { bound?.evaluateJs(script, onResult) }
    }

    override fun close() {
        // If close() lands before the Activity ever started, abandon the pending
        // launch so the static slot doesn't keep this session (and its host
        // listener/handlers) pinned.
        if (WebViewLaunchBridge.pending === this) {
            WebViewLaunchBridge.pending = null
            pendingRequest = null
        }
        val bound = activity
        main.post { bound?.finish() }
    }

    // The first request to load, read once by WebViewActivity on create.
    internal var pendingRequest: WebViewRequest? = null

    private companion object {
        const val ERROR_NO_ACTIVITY = "no_activity"
    }
}
