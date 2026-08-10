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
 * @param bridgeHosts exact HTTPS scheme/host identities allowed to dispatch
 *   native bridge messages. This is required so bridge authorization cannot be
 *   forgotten accidentally. An explicitly empty set disables bridge attachment.
 *   URL paths, queries, fragments and ports do not participate in this policy.
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
    val bridgeHosts: Set<TrustedWebHost>,
    val userScripts: List<String> = emptyList(),
    val javaScriptEnabled: Boolean = true,
    val domStorageEnabled: Boolean = true,
)

/**
 * Exact HTTPS scheme/host identity used to authorize WebView bridge calls.
 * Deliberately not a data class: its internal constructor must not be bypassed
 * by a generated public `copy()` method.
 */
class TrustedWebHost internal constructor(
    val scheme: String,
    val host: String,
) {
    override fun equals(other: Any?): Boolean =
        other is TrustedWebHost && scheme == other.scheme && host == other.host

    override fun hashCode(): Int = 31 * scheme.hashCode() + host.hashCode()

    override fun toString(): String = "$scheme://$host"
}

internal data class ParsedUrlAuthority(
    val scheme: String?,
    val host: String?,
    val hasUserInfo: Boolean,
)

/** Platform parser boundary. Security validation and normalization stay in common code. */
internal expect fun parseUrlAuthority(url: String): ParsedUrlAuthority?

/**
 * Parses an absolute HTTPS URL into the scheme/host identity used by the bridge
 * policy. Invalid URLs and URLs containing user information fail closed.
 */
fun trustedWebHostOf(url: String): TrustedWebHost? {
    val parsed = parseUrlAuthority(url) ?: return null
    if (parsed.hasUserInfo) return null
    return trustedWebHostOf(parsed.scheme, parsed.host)
}

/** Normalizes components supplied by a platform URL/WebView engine. */
internal fun trustedWebHostOf(schemeValue: String?, hostValue: String?): TrustedWebHost? {
    val scheme = schemeValue?.lowercase() ?: return null
    if (scheme != HTTPS_SCHEME) return null
    val host = hostValue
        ?.lowercase()
        ?.trimEnd('.')
        ?.takeIf { it.isNotBlank() }
        ?: return null
    return TrustedWebHost(scheme = scheme, host = host)
}

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
 * Whether a bridge message from [currentUrl] is authorized by [allowedHosts].
 * Both the allowlist and current URL are structured scheme/host identities;
 * malformed and non-HTTPS URLs fail closed.
 */
internal fun isBridgeHostAllowed(
    allowedHosts: Set<TrustedWebHost>,
    sourceScheme: String?,
    sourceHost: String?,
): Boolean = trustedWebHostOf(sourceScheme, sourceHost) in allowedHosts

private const val HTTPS_SCHEME = "https"
