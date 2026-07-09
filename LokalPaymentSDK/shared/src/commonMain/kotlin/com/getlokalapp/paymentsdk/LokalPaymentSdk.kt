package com.getlokalapp.paymentsdk

import com.getlokalapp.paymentsdk.model.LokalPaymentResult
import com.getlokalapp.paymentsdk.model.PaymentError
import com.getlokalapp.paymentsdk.model.PaymentGateway
import com.getlokalapp.paymentsdk.model.PaymentOrder
import com.getlokalapp.paymentsdk.model.PaymentResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Entry point for the shared Lokal Payment SDK.
 *
 * The host calls its own backend to create an order, decodes that response
 * into a [PaymentOrder], then hands it to [pay] — always the same call,
 * regardless of which gateway the backend picked. [pay] reads
 * `PaymentOrder.gateway`
 * and routes to whichever [PaymentGatewayHandler] is currently registered
 * for it. There's no list to build: constructing a gateway module's own SDK
 * class (e.g. `RazorpayCheckoutSdk()`) registers it automatically — see
 * [register]. LokalPaymentSdk only ever knows about the gateway modules
 * the host actually included and instantiated.
 */
object LokalPaymentSdk {

    private val handlers = mutableMapOf<PaymentGateway, PaymentGatewayHandler>()

    /**
     * Called by a gateway module's own SDK class in its constructor — not
     * something a host normally calls directly. Registering again for the
     * same gateway (e.g. a new RazorpayCheckoutSdk built after the previous
     * one was disposed) replaces whichever handler was previously registered
     * for it.
     */
    fun register(handler: PaymentGatewayHandler) {
        handlers[handler.gateway] = handler
    }

    /**
     * Called when a handler's underlying instance is going away, so
     * LokalPaymentSdk doesn't hold a stale reference. Only removes it if
     * it's still the current registration for its gateway — guards against
     * a late unregister from an old instance clobbering a newer one that
     * already replaced it.
     */
    fun unregister(handler: PaymentGatewayHandler) {
        if (handlers[handler.gateway] === handler) handlers.remove(handler.gateway)
    }

    /**
     * Snapshot of gateways currently registered — i.e. the gateway SDK
     * classes the host has actually constructed right now. This changes as
     * handlers register/dispose (e.g. across an Activity recreation), so
     * treat it as a live query, not a fixed capability list. Useful for a
     * host that wants to check what's available (e.g. to show/hide a button)
     * without holding onto every handler instance itself.
     */
    fun registeredGateways(): Set<PaymentGateway> = handlers.keys.toSet()

    /**
     * Runs a payment for the given order and emits exactly one terminal
     * [LokalPaymentResult] — the gateway-agnostic [PaymentResult] plus the
     * resolved gateway — before completing.
     *
     * @param order the host's create-order response, already decoded into a [PaymentOrder]
     */
    fun pay(order: PaymentOrder): Flow<LokalPaymentResult> {
        val handler = handlers[order.gateway]
            ?: return flowOf(
                LokalPaymentResult(
                    gateway = order.gateway,
                    result = PaymentResult.Failure(
                        PaymentError(
                            code = "unsupported_gateway",
                            message = "No PaymentGatewayHandler registered for gateway " +
                                "${order.gateway}. Did you forget to include " +
                                "and construct that gateway's SDK class?",
                        ),
                    ),
                ),
            )
        return handler.pay(order.gatewayConfig).map { LokalPaymentResult(order.gateway, it) }
    }

    const val VERSION: String = "0.0.1"
}
