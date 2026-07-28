package com.getlokalapp.paymentsdk.webcheckout

import com.getlokalapp.paymentsdk.LokalPaymentSdk
import com.getlokalapp.paymentsdk.PaymentGatewayHandler
import com.getlokalapp.paymentsdk.model.CancelReason
import com.getlokalapp.paymentsdk.model.GatewayMetadata
import com.getlokalapp.paymentsdk.model.PaymentError
import com.getlokalapp.paymentsdk.model.PaymentGateway
import com.getlokalapp.paymentsdk.model.PaymentGatewayEvent
import com.getlokalapp.paymentsdk.model.PaymentResult
import com.getlokalapp.paymentsdk.parseGatewayConfigOrFail
import com.getlokalapp.paymentsdk.webview.WebViewConfig
import com.getlokalapp.paymentsdk.webview.WebViewListener
import com.getlokalapp.paymentsdk.webview.WebViewRequest
import com.getlokalapp.paymentsdk.webview.createWebViewSession
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.JsonObject

/** GatewayMetadata.vendorSdkVersion sentinel — this gateway wraps no vendor SDK. */
private const val NO_VENDOR_SDK = "none"

/** JS global the page reaches the bridge through; the RN shim relays to it. */
private const val BRIDGE_NAME = "LokalBridge"

/**
 * Singleton handler for [PaymentGateway.WEB_CHECKOUT] — runs the backend-built
 * hosted gateway web app (payment-web) inside the `:webview` module and maps the
 * one event the page reports to a terminal [PaymentResult]. Provider-agnostic:
 * the provider (dodo/stripe/…) is chosen by the backend and baked into the
 * gateway URL; this SDK never names it, and never sees card data or secrets.
 *
 * Registers with [LokalPaymentSdk] in its `init` block at app startup with zero
 * host code — `WebCheckoutInitializer` (AndroidX App Startup) on Android, the
 * `@EagerInitialization` hook in `WebCheckoutEagerInit.kt` on iOS. Works on both
 * platforms (the WebView is cross-platform), so there is no `registerUnavailable`.
 */
internal object WebCheckoutSdk : PaymentGatewayHandler {

    override val gateway: PaymentGateway = PaymentGateway.WEB_CHECKOUT

    override val metadata: GatewayMetadata = GatewayMetadata(
        moduleVersion = MODULE_VERSION,
        vendorSdkVersion = NO_VENDOR_SDK,
    )

    init {
        LokalPaymentSdk.register(this)
    }

    /**
     * Opens the backend-built hosted-gateway URL in a WebView and emits exactly
     * one terminal [PaymentResult] from the one event the page reports:
     * `SUCCESS → Success`, `FAILED`/`EXPIRED`/`GATEWAY_ERROR → Failure`,
     * `PROCESSING`/`PENDING → Pending`, `CANCELLED` (or the view being dismissed
     * with no event) `→ Cancelled`. The result is **advisory** — as with UPI
     * intent, the host must confirm final state with its own backend.
     */
    override fun pay(gatewayConfig: JsonObject): Flow<PaymentGatewayEvent> = callbackFlow {
        val config = parseGatewayConfigOrFail { gatewayConfig.toWebCheckoutConfig() } ?: return@callbackFlow

        // First-wins: the page posts one event and closes; a bridge event then
        // dismisses the view, whose onClosed must not overwrite the real result.
        var settled = false
        fun emit(result: PaymentResult) {
            if (settled) return
            settled = true
            trySend(PaymentGatewayEvent.Terminal(result))
            close()
        }

        val webConfig = WebViewConfig(
            bridgeName = BRIDGE_NAME,
            handlers = webCheckoutHandlers(onResult = { emit(it) }),
            userScripts = listOf(REACT_NATIVE_BRIDGE_SHIM),
            allowedOrigins = originOf(config.gatewayUrl)?.let { listOf(it) },
            listener = object : WebViewListener {
                // Dismissed (Android hardware back) with no terminal event yet →
                // user cancellation.
                override fun onClosed() {
                    emit(PaymentResult.Cancelled(CancelReason.USER_DISMISSED))
                }

                override fun onError(code: String, message: String) {
                    emit(PaymentResult.Failure(PaymentError(code = code, message = message)))
                }
            },
        )

        val session = createWebViewSession(webConfig)
        session.load(WebViewRequest.Url(config.gatewayUrl))

        awaitClose { session.close() }
    }
}
