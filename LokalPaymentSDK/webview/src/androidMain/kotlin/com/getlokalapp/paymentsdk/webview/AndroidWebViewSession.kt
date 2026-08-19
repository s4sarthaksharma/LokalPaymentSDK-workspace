package com.getlokalapp.paymentsdk.webview

import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.getlokalapp.paymentsdk.hostcontext.ActivityTracker
import com.getlokalapp.paymentsdk.infrastructure.BridgeErrorCodes
import com.getlokalapp.paymentsdk.infrastructure.SinglePendingOperation

actual fun createWebViewSession(config: WebViewConfig): WebViewSession = AndroidWebViewSession(config)

/**
 * The single in-flight WebView presentation. The [WebViewConfig] and its handlers/listener can't ride
 * along in the launch Intent (lambdas aren't Parcelable), so the session parks itself here for
 * [WebViewActivity] to pick up — the same process-local pattern the payment gateways use. One WebView
 * at a time, so a single slot suffices.
 *
 * This module drives its own close/terminal bookkeeping (see [WebViewActivity.onDestroy]) rather than
 * the operation's once-only gate; it uses the slot purely as the launch handoff it always was.
 */
internal val webViewLaunchOperation = SinglePendingOperation<AndroidWebViewSession, WebViewListener>()

/**
 * Drives a WebView on Android by launching [WebViewActivity], an internal proxy
 * that owns the `android.webkit.WebView`. The Activity to launch from comes from
 * [ActivityTracker] (`:shared`'s hostcontext utility) at call time, not a
 * host-supplied handle. Once the Activity is up it binds itself back via
 * [activity] so [evaluateJavascript] / [close] can reach the live WebView.
 */
internal class AndroidWebViewSession(internal val config: WebViewConfig) : WebViewSession {

    // Set by WebViewActivity.onCreate, cleared on its onDestroy. Read/written
    // from the main thread only (launch, bind, teardown all happen there).
    @Volatile
    internal var activity: WebViewActivity? = null

    /**
     * This presentation's slot in [webViewLaunchOperation], kept so both this session and
     * [WebViewActivity] can release it. Identity-checked on release, so a stale one can never clear a
     * later presentation's slot.
     */
    @Volatile
    internal var operationEntry: SinglePendingOperation.Entry<AndroidWebViewSession, WebViewListener>? = null

    /** True when teardown was initiated by [close], not by a user dismissal. */
    @Volatile
    internal var closeRequested: Boolean = false

    private val main = Handler(Looper.getMainLooper())

    override fun load(request: WebViewRequest) {
        val bound = activity
        if (bound != null) {
            main.post { bound.loadRequest(request) }
            return
        }
        // closeRequested belongs to one presentation. A session may be loaded
        // again after close(), and that new presentation must report a later
        // user dismissal normally.
        closeRequested = false
        val host = ActivityTracker.current
        if (host == null) {
            config.listener?.safeError(ERROR_NO_ACTIVITY, "webview_no_activity")
            return
        }
        pendingRequest = request
        val entry = webViewLaunchOperation.tryInstall(this, config.listener)
        if (entry == null) {
            pendingRequest = null
            config.listener?.safeError(BridgeErrorCodes.HANDOFF_IN_PROGRESS, BridgeErrorCodes.HANDOFF_IN_PROGRESS)
            return
        }
        operationEntry = entry
        try {
            host.startActivity(Intent(host, WebViewActivity::class.java))
        } catch (t: Throwable) {
            webViewLaunchOperation.clearIfOwned(entry)
            operationEntry = null
            pendingRequest = null
            config.listener?.safeError(BridgeErrorCodes.ACTIVITY_LAUNCH_FAILED, BridgeErrorCodes.ACTIVITY_LAUNCH_FAILED)
        }
    }

    override fun evaluateJavascript(script: String, onResult: ((String?) -> Unit)?) {
        val bound = activity
        main.post { bound?.evaluateJs(script, onResult) }
    }

    override fun close() {
        closeRequested = true
        // If close() lands before the Activity ever started, abandon the pending
        // launch so the static slot doesn't keep this session (and its host
        // listener/handlers) pinned.
        operationEntry?.let { entry ->
            if (webViewLaunchOperation.clearIfOwned(entry)) {
                operationEntry = null
                pendingRequest = null
            }
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
