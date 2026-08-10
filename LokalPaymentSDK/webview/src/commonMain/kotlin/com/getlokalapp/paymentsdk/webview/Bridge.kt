package com.getlokalapp.paymentsdk.webview

import com.getlokalapp.paymentsdk.json.lenientJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

// ---------------------------------------------------------------------------
// Transport channel + injected JS shim (page side of the bridge)
// ---------------------------------------------------------------------------

/**
 * The single native transport channel both platforms funnel bridge traffic
 * through. Distinct from [WebViewConfig.bridgeName] (the page-facing global):
 * the page calls `window.<bridgeName>.postMessage(...)`, the shim relays it over
 * this fixed channel — Android as an AndroidX WebKit `WebMessageListener`
 * object of this name, iOS as `window.webkit.messageHandlers.<this>`.
 */
internal const val TRANSPORT_NAME = "LokalNativeTransport"

/** Global JS function the native side calls to resolve an awaited reply. */
internal const val REPLY_FN = "__lokalBridgeReply__"

/** Android shim: relays over AndroidX WebKit's injected WebMessageListener object. */
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

// ---------------------------------------------------------------------------
// Envelope parsing + routing (native side of the bridge)
// ---------------------------------------------------------------------------

/**
 * The envelope the JS shim posts over the transport channel:
 * `{name, payload, id}`. [payload] is whatever value the page passed (any JSON);
 * [id] correlates an awaited reply.
 */
@Serializable
internal class BridgeEnvelope(
    val name: String,
    val payload: JsonElement? = null,
    val id: String? = null,
)

/**
 * Platform-agnostic core of the bridge: parses an incoming envelope string,
 * routes it to the matching [JsBridgeHandler], and builds the reply JS. The
 * only per-platform part — actually evaluating that JS in the page — is injected
 * as [evaluate]. Both Android and iOS construct one of these and feed it the raw
 * message string their transport receives.
 */
internal class BridgeDispatcher(
    config: WebViewConfig,
    private val evaluate: (String) -> Unit,
) {
    private val handlersByName: Map<String, JsBridgeHandler> = config.handlers.associateBy { it.name }

    fun dispatch(rawMessage: String) {
        val envelope = try {
            lenientJson.decodeFromString(BridgeEnvelope.serializer(), rawMessage)
        } catch (_: Exception) {
            // Malformed message from the page — ignore rather than crash the host.
            return
        }
        val handler = handlersByName[envelope.name] ?: return
        val payload = envelope.payload?.toString() ?: "null"
        val id = envelope.id
        handler.onMessage(payload) { result ->
            if (id != null) evaluate(replyScript(id, result))
        }
    }

    /**
     * JS that resolves the awaiting promise. [id] and [result] are encoded as
     * JSON primitives so any quotes/newlines are escaped safely — no manual
     * string interpolation into the page.
     */
    private fun replyScript(id: String, result: String): String =
        "window.$REPLY_FN(${JsonPrimitive(id)}, ${JsonPrimitive(result)});"
}
