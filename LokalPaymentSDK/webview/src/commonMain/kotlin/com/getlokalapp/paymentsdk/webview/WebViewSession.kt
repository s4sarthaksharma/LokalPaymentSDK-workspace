package com.getlokalapp.paymentsdk.webview

/**
 * A single presented WebView with a JS bridge. The SDK owns and presents the
 * view itself (Android: an internal proxy Activity; iOS: presented onto the
 * topmost view controller) — consumers never touch a platform view, matching
 * the rest of LokalPaymentSDK.
 *
 * Obtain one from [createWebViewSession], drive it with [load], talk to the page
 * via [evaluateJavascript] (and [WebViewConfig.handlers] for the reverse
 * direction), and tear it down with [close]. Public — a separate gateway module
 * consumes this, so `internal` would hide it.
 */
interface WebViewSession {

    /**
     * Loads [request]. The first call presents the WebView; subsequent calls
     * navigate the already-presented one.
     */
    fun load(request: WebViewRequest)

    /**
     * Runs [script] in the page. [onResult] (if given) receives the evaluated
     * result as a string, on the main thread. No-op if the WebView isn't
     * currently presented.
     */
    fun evaluateJavascript(script: String, onResult: ((String?) -> Unit)? = null)

    /** Dismisses the WebView. Triggers [WebViewListener.onClosed]. */
    fun close()
}

/**
 * Creates a platform [WebViewSession] for [config]. Backed by
 * `android.webkit.WebView` on Android and `WKWebView` on iOS.
 */
expect fun createWebViewSession(config: WebViewConfig): WebViewSession
