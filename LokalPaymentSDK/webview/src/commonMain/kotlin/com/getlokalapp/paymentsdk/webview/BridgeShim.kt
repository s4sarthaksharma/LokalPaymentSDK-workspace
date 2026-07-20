package com.getlokalapp.paymentsdk.webview

/**
 * The single native transport channel both platforms funnel bridge traffic
 * through. Distinct from [WebViewConfig.bridgeName] (the page-facing global):
 * the page calls `window.<bridgeName>.postMessage(...)`, the shim relays it over
 * this fixed channel — Android as an `@JavascriptInterface` object of this name,
 * iOS as `window.webkit.messageHandlers.<this>`.
 */
internal const val TRANSPORT_NAME = "LokalNativeTransport"

/** Global JS function the native side calls to resolve an awaited reply. */
internal const val REPLY_FN = "__lokalBridgeReply__"

/** Android shim: relays over `addJavascriptInterface`. */
internal fun androidBridgeShim(bridgeName: String): String =
    bridgeShim(bridgeName, "$TRANSPORT_NAME.postMessage(envelope);")

/** iOS shim: relays over a `WKScriptMessageHandler`. */
internal fun iosBridgeShim(bridgeName: String): String =
    bridgeShim(bridgeName, "window.webkit.messageHandlers.$TRANSPORT_NAME.postMessage(envelope);")

/**
 * Installs `window.<bridgeName>` with a promise-returning `postMessage(name,
 * payload)` that serializes an envelope and relays it via [dispatch] (the only
 * per-platform difference). Native resolves the promise by calling
 * `window.$REPLY_FN(id, result)`. Guards against double-injection.
 */
private fun bridgeShim(bridgeName: String, dispatch: String): String = """
(function () {
  if (window["$bridgeName"]) return;
  var pending = {};
  var counter = 0;
  window["$bridgeName"] = {
    postMessage: function (name, payload) {
      var id = "m" + (counter++);
      var envelope = JSON.stringify({
        name: name,
        payload: (payload === undefined ? null : payload),
        id: id
      });
      return new Promise(function (resolve) {
        pending[id] = resolve;
        $dispatch
      });
    }
  };
  window.$REPLY_FN = function (id, result) {
    var cb = pending[id];
    if (cb) { delete pending[id]; cb(result); }
  };
})();
""".trimIndent()
