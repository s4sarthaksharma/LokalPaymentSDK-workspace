package com.getlokalapp.paymentsdk.webview

/**
 * Handles one class of message sent from the web page. The page calls
 * `window.<bridgeName>.postMessage("<name>", payload)`; the message is routed to
 * the handler whose [name] matches.
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
