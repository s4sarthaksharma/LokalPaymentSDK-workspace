package com.getlokalapp.paymentsdk.webview

/**
 * Construction-time configuration for a [WebViewSession]. Immutable; passed once
 * to [createWebViewSession].
 *
 * @param bridgeName the JS global the page uses to reach native —
 *   `window.<bridgeName>.postMessage(name, payload)`. Defaults to `"LokalBridge"`.
 * @param handlers message handlers, routed by [JsBridgeHandler.name]. Duplicate
 *   names: last one wins.
 * @param listener navigation / lifecycle callbacks (main thread).
 * @param allowedOrigins if non-null, bridge messages are only dispatched when the
 *   WebView's current URL starts with one of these prefixes; other messages are
 *   dropped. `null` (default) allows all — set this whenever the page loads
 *   untrusted or third-party content.
 * @param javaScriptEnabled / domStorageEnabled WebView engine toggles.
 */
class WebViewConfig(
    val bridgeName: String = "LokalBridge",
    val handlers: List<JsBridgeHandler> = emptyList(),
    val listener: WebViewListener? = null,
    val allowedOrigins: List<String>? = null,
    val javaScriptEnabled: Boolean = true,
    val domStorageEnabled: Boolean = true,
)
