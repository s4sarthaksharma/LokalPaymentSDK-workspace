package com.getlokalapp.paymentsdk.webview

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.getlokalapp.util.Log

/**
 * Internal proxy Activity that owns the `android.webkit.WebView`. Keeping it
 * here means host apps never supply or receive a WebView — mirrors
 * `:razorpay-checkout`'s RazorpayCheckoutActivity, except this one shows the
 * WebView full-screen (it's the actual UI, not an invisible bridge). Picks up
 * the in-flight [AndroidWebViewSession] from [webViewLaunchHandoff] and binds
 * itself back to it so the session can drive the live WebView.
 */
@SuppressLint("SetJavaScriptEnabled")
internal class WebViewActivity : ComponentActivity() {

    private var session: AndroidWebViewSession? = null
    private var webView: WebView? = null
    private var bridgeAttached = false
    private var terminalFailureReported = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val current = webViewLaunchHandoff.take()
        if (current == null) {
            // No in-flight launch — e.g. process recreated after death. Nothing
            // to drive; bail.
            finish()
            return
        }
        session = current
        current.activity = this
        val config = current.config

        // targetSdk 35+ forces edge-to-edge, so opt in explicitly and inset the
        // WebView ourselves — otherwise the page draws under the status/nav bars.
        // A white window backing keeps the bar regions clean with dark icons.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setBackgroundDrawable(ColorDrawable(Color.WHITE))
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = true

        val wv = WebView(this)
        webView = wv

        // Back navigates the WebView history first; when there's nowhere to go
        // back, disable this callback and re-dispatch so the platform default
        // (finish) runs.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val current = webView
                if (current != null && current.canGoBack()) {
                    current.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        wv.settings.javaScriptEnabled = config.javaScriptEnabled
        wv.settings.domStorageEnabled = config.domStorageEnabled

        if (config.handlers.isNotEmpty() && config.bridgeHosts.isNotEmpty()) {
            if (!attachBridge(wv, config)) {
                terminalFailureReported = true
                val providerVersion = WebViewCompat.getCurrentWebViewPackage(this)?.versionName ?: "unknown"
                Log.w {
                    "[WebView] $BRIDGE_UNAVAILABLE, providerVersion=$providerVersion"
                }
                config.listener?.onError(BRIDGE_UNAVAILABLE, "Secure WebView messaging is unavailable.")
                finish()
                return
            }
            bridgeAttached = true
        }

        wv.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                // Inject the bridge as early as we can without androidx.webkit's
                // document-start hook. Good enough for pages that call the bridge
                // after DOM ready; see plan's known-limitations note.
                wv.evaluateJavascript(androidBridgeShim(config.bridgeName), null)
                config.userScripts.forEach { wv.evaluateJavascript(it, null) }
                config.listener?.onPageStarted(url.orEmpty())
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                config.listener?.onPageFinished(url.orEmpty())
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                return config.listener?.onNavigation(url) ?: false
            }
        }

        setContentView(wv)
        // Pad the WebView by the system bars / cutout / IME so no content is
        // hidden under them and the keyboard pushes the page up rather than over.
        ViewCompat.setOnApplyWindowInsetsListener(wv) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.ime(),
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(wv)

        current.pendingRequest?.let { loadRequest(it) }
        current.pendingRequest = null
    }

    internal fun loadRequest(request: WebViewRequest) {
        val wv = webView ?: return
        when (request) {
            is WebViewRequest.Url ->
                if (request.headers.isEmpty()) wv.loadUrl(request.url)
                else wv.loadUrl(request.url, request.headers)
        }
    }

    internal fun evaluateJs(script: String, onResult: ((String?) -> Unit)?) {
        webView?.evaluateJavascript(script) { onResult?.invoke(it) }
    }

    override fun onDestroy() {
        val current = session
        if (current?.activity === this) current.activity = null
        current?.let(webViewLaunchHandoff::clearIfOwned)
        current?.pendingRequest = null
        if (!terminalFailureReported && current?.closeRequested != true) {
            current?.config?.listener?.onClosed()
        }
        session = null
        // Full WebView teardown: detach the JS bridge, stop loads, and remove it
        // from the view tree before destroy() so a stray reference can't keep the
        // (Activity-context) WebView — and thus the Activity — alive.
        webView?.let { wv ->
            wv.stopLoading()
            if (bridgeAttached) {
                WebViewCompat.removeWebMessageListener(wv, TRANSPORT_NAME)
                bridgeAttached = false
            }
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.destroy()
        }
        webView = null
        super.onDestroy()
    }
}

/**
 * Attaches a frame-aware AndroidX WebKit message listener. The WebView engine
 * supplies the sending frame's origin and whether it is the main frame, so no
 * authorization decision is based on the top-level WebView URL.
 */
private fun attachBridge(webView: WebView, config: WebViewConfig): Boolean {
    if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return false

    val dispatcher = BridgeDispatcher(config) { script ->
        webView.post { webView.evaluateJavascript(script, null) }
    }
    WebViewCompat.addWebMessageListener(
        webView,
        TRANSPORT_NAME,
        setOf("*"),
    ) { _, message, sourceOrigin, isMainFrame, _ ->
        if (!isMainFrame) return@addWebMessageListener
        if (!isBridgeHostAllowed(config.bridgeHosts, sourceOrigin.scheme, sourceOrigin.host)) {
            return@addWebMessageListener
        }
        message.data?.let(dispatcher::dispatch)
    }
    return true
}

private const val BRIDGE_UNAVAILABLE = "secure_web_message_unavailable"
