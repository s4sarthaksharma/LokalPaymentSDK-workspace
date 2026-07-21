package com.getlokalapp.paymentsdk.webcheckout

import com.getlokalapp.paymentsdk.json.lenientJson
import com.getlokalapp.paymentsdk.model.CancelReason
import com.getlokalapp.paymentsdk.model.ClientStatus
import com.getlokalapp.paymentsdk.model.PaymentError
import com.getlokalapp.paymentsdk.model.PaymentResult
import com.getlokalapp.paymentsdk.webview.JsBridgeHandler
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

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
 * One [JsBridgeHandler] per web-app event, each mapping the event to a terminal
 * [PaymentResult] and handing it to [onResult]. The web app posts exactly one
 * event per attempt and [onResult] is first-wins, so at most one fires. `reply`
 * is unused — the RN contract is fire-and-forget. `SUCCESS` maps to `Success`
 * (advisory — the host still confirms with its backend); `PROCESSING`/`PENDING`
 * map to `Pending`; `FAILED`/`EXPIRED`/`GATEWAY_ERROR` to `Failure`.
 */
internal fun webCheckoutHandlers(onResult: (PaymentResult) -> Unit): List<JsBridgeHandler> =
    listOf(
        eventHandler(EVENT_SUCCESS, onResult) { p ->
            PaymentResult.Success(paymentId = p.str("paymentId").orEmpty(), orderId = null, signature = "")
        },
        eventHandler(EVENT_FAILED, onResult) { p ->
            PaymentResult.Failure(PaymentError(code = p.str("status") ?: "failed", message = "payment_failed"))
        },
        eventHandler(EVENT_EXPIRED, onResult) {
            PaymentResult.Failure(PaymentError(code = "expired", message = "payment_expired"))
        },
        eventHandler(EVENT_PROCESSING, onResult) { p ->
            PaymentResult.Pending(txnRef = p.str("paymentId").orEmpty(), clientHint = ClientStatus.UNKNOWN)
        },
        eventHandler(EVENT_PENDING, onResult) { p ->
            PaymentResult.Pending(txnRef = p.str("paymentId").orEmpty(), clientHint = ClientStatus.UNKNOWN)
        },
        eventHandler(EVENT_CANCELLED, onResult) {
            PaymentResult.Cancelled(CancelReason.USER_DISMISSED)
        },
        eventHandler(EVENT_ERROR, onResult) { p ->
            PaymentResult.Failure(PaymentError(code = p.str("reason") ?: "gateway_error", message = "payment_gateway_error"))
        },
    )

private fun eventHandler(
    event: String,
    onResult: (PaymentResult) -> Unit,
    map: (JsonObject) -> PaymentResult,
): JsBridgeHandler = object : JsBridgeHandler {
    override val name: String = event

    override fun onMessage(payload: String, reply: (String) -> Unit) {
        val obj = runCatching { lenientJson.parseToJsonElement(payload).jsonObject }.getOrNull()
            ?: JsonObject(emptyMap())
        onResult(map(obj))
    }
}

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
