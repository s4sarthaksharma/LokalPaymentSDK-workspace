package com.getlokalapp.paymentsdk.webview

/**
 * Lifecycle and navigation callbacks for a [WebViewSession]. All methods have
 * no-op defaults so consumers override only what they need. Invoked on the main
 * thread on both platforms.
 */
interface WebViewListener {

    /** A navigation to [url] has started (main-frame). */
    fun onPageStarted(url: String) {}

    /** The main-frame navigation to [url] finished loading. */
    fun onPageFinished(url: String) {}

    /**
     * A navigation to [url] is about to happen (e.g. a redirect or a custom
     * scheme). Return `true` to intercept it — the WebView cancels the load and
     * the consumer handles [url] itself (the common hook for payment redirect /
     * `upi://` / app-scheme handoff). Return `false` (default) to let the
     * WebView load it normally.
     */
    fun onNavigation(url: String): Boolean = false

    /**
     * The WebView was dismissed by the user or system (for example back/swipe).
     * A caller-initiated [WebViewSession.close] is deliberately silent.
     */
    fun onClosed() {}

    /** A session-level failure with a machine-checkable [code] and a message. */
    fun onError(code: String, message: String) {}
}
