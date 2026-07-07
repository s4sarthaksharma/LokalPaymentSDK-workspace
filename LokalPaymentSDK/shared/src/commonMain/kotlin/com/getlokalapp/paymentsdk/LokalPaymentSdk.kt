package com.getlokalapp.paymentsdk

import com.getlokalapp.paymentsdk.model.PaymentError
import com.getlokalapp.paymentsdk.model.PaymentGateway
import com.getlokalapp.paymentsdk.model.PaymentResult
import com.getlokalapp.paymentsdk.model.parseCreateOrderResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Entry point for the shared Lokal Payment SDK.
 *
 * The host calls its own backend to create an order, then hands the raw
 * create-order response to [pay] — always the same call, regardless of
 * which gateway the backend picked. [pay] reads `CreateOrderResponse.gateway`
 * and routes to whichever [PaymentGatewayHandler] is currently registered
 * for it. There's no list to build: constructing a gateway module's own SDK
 * class (e.g. `RazorpayCheckoutSdk(presenter)`) registers it automatically —
 * see [register]. LokalPaymentSdk only ever knows about the gateway modules
 * the host actually included and instantiated.
 */
object LokalPaymentSdk {

    private val handlers = mutableMapOf<PaymentGateway, PaymentGatewayHandler>()

    /**
     * Called by a gateway module's own SDK class in its constructor — not
     * something a host normally calls directly. Registering again for the
     * same gateway (e.g. a new RazorpayCheckoutSdk built after an Activity
     * recreation) replaces whichever handler was previously registered for
     * it.
     */
    fun register(handler: PaymentGatewayHandler) {
        handlers[handler.gateway] = handler
    }

    /**
     * Called when a handler's underlying platform context (an Activity, a
     * PaymentPresenter) is going away, so LokalPaymentSdk doesn't hold a
     * stale reference. Only removes it if it's still the current
     * registration for its gateway — guards against a late unregister from
     * an old instance clobbering a newer one that already replaced it.
     */
    fun unregister(handler: PaymentGatewayHandler) {
        if (handlers[handler.gateway] === handler) handlers.remove(handler.gateway)
    }

    /**
     * Runs a payment for the given create-order response and emits exactly one
     * terminal [PaymentResult] (Success / Cancelled / Failure) before completing.
     *
     * @param orderResponseJson the raw create-order response body from the host's backend
     */
    fun pay(orderResponseJson: String): Flow<PaymentResult> {
        val response = parseCreateOrderResponse(orderResponseJson)
        val gateway = PaymentGateway.fromValue(response.gateway)
        val handler = handlers[gateway]
            ?: return flowOf(
                PaymentResult.Failure(
                    PaymentError(
                        code = "unsupported_gateway",
                        message = "No PaymentGatewayHandler registered for gateway " +
                            "${gateway ?: response.gateway}. Did you forget to include " +
                            "and construct that gateway's SDK class?",
                    ),
                ),
            )
        return handler.pay(orderResponseJson)
    }

    const val VERSION: String = "0.0.1"
}
