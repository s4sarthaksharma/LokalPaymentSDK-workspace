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
 * @param userScripts extra JS injected at document start, in order, right after
 *   the built-in bridge shim (so it may reference `window.<bridgeName>`). Use it
 *   to install compatibility shims — e.g. a `window.ReactNativeWebView` that
 *   relays to the Lokal bridge — without baking that knowledge into `:webview`.
 * @param javaScriptEnabled / domStorageEnabled WebView engine toggles.
 */
class WebViewConfig(
    val bridgeName: String = "LokalBridge",
    val handlers: List<JsBridgeHandler> = emptyList(),
    val listener: WebViewListener? = null,
    val allowedOrigins: List<String>? = null,
    val userScripts: List<String> = emptyList(),
    val javaScriptEnabled: Boolean = true,
    val domStorageEnabled: Boolean = true,
)

/**
 * Handles one class of message sent from the web page. The page calls
 * `window.<bridgeName>.postMessage("<name>", payload)`; the message is routed to
 * the handler whose [name] matches (see [WebViewConfig.handlers]).
 *
 * [onMessage] receives [payload] as a JSON string (the value the page passed,
 * re-serialized — normalized identically on Android and iOS). Calling [reply]
 * resolves the `Promise` that `postMessage` returned on the JS side with the
 * given string, so the page can `await` a native response. [reply] may be
 * called later from any thread and is a no-op if the page didn't await; call it
 * at most once.
 */
interface JsBridgeHandler {
    val name: String
    fun onMessage(payload: String, reply: (String) -> Unit)
}

/**
 * Whether a bridge message from [currentUrl] is allowed given [allowed]
 * (typically [WebViewConfig.allowedOrigins]). `null` allows everything;
 * otherwise the current URL must start with one of the prefixes. Prefix match
 * (not full origin parsing) — documented as a v1 simplification.
 */
internal fun isOriginAllowed(allowed: List<String>?, currentUrl: String?): Boolean {
    if (allowed == null) return true
    val url = currentUrl ?: return false
    return allowed.any { url.startsWith(it) }
}
