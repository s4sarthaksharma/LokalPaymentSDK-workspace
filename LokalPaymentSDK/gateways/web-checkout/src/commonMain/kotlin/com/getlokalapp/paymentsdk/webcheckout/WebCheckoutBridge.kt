package com.getlokalapp.paymentsdk.webcheckout

import com.getlokalapp.paymentsdk.json.lenientJson
import com.getlokalapp.paymentsdk.model.CancelReason
import com.getlokalapp.paymentsdk.model.ClientStatus
import com.getlokalapp.paymentsdk.model.PaymentError
import com.getlokalapp.paymentsdk.model.PaymentResult
import com.getlokalapp.paymentsdk.webview.JsBridgeHandler
import com.getlokalapp.util.Log
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

private const val TAG = "WebCheckout"

// The hosted gateway web app's event vocabulary (payment-web). The page reports
// via window.ReactNativeWebView.postMessage(JSON.stringify({ name, payload }));
// the shim below relays that over the Lokal bridge, whose {name, payload}
// envelope :webview's dispatcher routes by `name` to the matching handler here.
// See docs/web-checkout-gateway-plan.md.
private const val EVENT_SUCCESS = "PAYMENT_SUCCESS"
private const val EVENT_FAILED = "PAYMENT_FAILED"
private const val EVENT_PROCESSING = "PAYMENT_PROCESSING"
private const val EVENT_EXPIRED = "PAYMENT_EXPIRED"
private const val EVENT_PENDING = "PAYMENT_PENDING"
private const val EVENT_CANCELLED = "PAYMENT_CANCELLED"
private const val EVENT_ERROR = "PAYMENT_GATEWAY_ERROR"

/**
 * Injected at document start via [com.getlokalapp.paymentsdk.webview.WebViewConfig.userScripts].
 * Defines `window.ReactNativeWebView` — the contract the hosted page already
 * speaks — and relays each fire-and-forget message over `window.LokalBridge`,
 * whose `{name, payload}` envelope our dispatcher routes by name. Platform-
 * agnostic: it only touches `window.LokalBridge`, which `:webview` defines
 * identically on Android and iOS. Idempotent.
 */
internal const val REACT_NATIVE_BRIDGE_SHIM = """
(function () {
  if (window.ReactNativeWebView && window.ReactNativeWebView.__lokal) return;
  window.ReactNativeWebView = {
    __lokal: true,
    postMessage: function (raw) {
      try {
        var m = JSON.parse(raw);
        window.LokalBridge.postMessage(m.name, m.payload);
      } catch (e) {}
    }
  };
})();
"""

/**
 * Each web-app event mapped to the terminal [PaymentResult] it produces. Static
 * — the mappers don't depend on the per-call result sink, so they're built once
 * rather than on every `pay()`. `SUCCESS` maps to `Success` (advisory — the host
 * still confirms with its backend); `PROCESSING`/`PENDING` to `Pending`;
 * `FAILED`/`EXPIRED`/`GATEWAY_ERROR` to `Failure`; `CANCELLED` to `Cancelled`.
 */
private val EVENT_MAPPERS: Map<String, (JsonObject) -> PaymentResult> = mapOf(
    EVENT_SUCCESS to { p ->
        PaymentResult.Success(paymentId = p.str("paymentId").orEmpty(), orderId = null, signature = "")
    },
    EVENT_FAILED to { p ->
        PaymentResult.Failure(PaymentError(code = p.str("status") ?: "failed", message = "payment_failed"))
    },
    EVENT_EXPIRED to {
        PaymentResult.Failure(PaymentError(code = "expired", message = "payment_expired"))
    },
    EVENT_PROCESSING to ::pendingResult,
    EVENT_PENDING to ::pendingResult,
    EVENT_CANCELLED to {
        PaymentResult.Cancelled(CancelReason.USER_DISMISSED)
    },
    EVENT_ERROR to { p ->
        PaymentResult.Failure(PaymentError(code = p.str("reason") ?: "gateway_error", message = "payment_gateway_error"))
    },
)

private fun pendingResult(p: JsonObject): PaymentResult =
    PaymentResult.Pending(txnRef = p.str("paymentId").orEmpty(), clientHint = ClientStatus.UNKNOWN)

/**
 * One [JsBridgeHandler] per web-app event (see [EVENT_MAPPERS]), each handing its
 * mapped result to [onResult]. The web app posts exactly one event per attempt
 * and [onResult] is first-wins, so at most one fires. `reply` is unused — the RN
 * contract is fire-and-forget.
 */
internal fun webCheckoutHandlers(onResult: (PaymentResult) -> Unit): List<JsBridgeHandler> =
    EVENT_MAPPERS.map { (event, map) -> eventHandler(event, onResult, map) }

private fun eventHandler(
    event: String,
    onResult: (PaymentResult) -> Unit,
    map: (JsonObject) -> PaymentResult,
): JsBridgeHandler = object : JsBridgeHandler {
    override val name: String = event

    override fun onMessage(payload: String, reply: (String) -> Unit) {
        Log.d { "[$TAG] page reported event=$event" }
        val obj = runCatching { lenientJson.parseToJsonElement(payload).jsonObject }.getOrNull()
            ?: JsonObject(emptyMap())
        onResult(map(obj))
    }
}

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
