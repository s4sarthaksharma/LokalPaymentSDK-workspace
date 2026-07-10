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
 * for it. There's no list to build: each gateway module's singleton handler
 * registers itself at app startup (see [PaymentGatewayHandler]'s kdoc for
 * the per-platform bootstrap), so LokalPaymentSdk only ever knows about the
 * gateway modules the host actually included. Registration is app-lifetime;
 * there is no unregister.
 */
object LokalPaymentSdk {

    private val handlers = mutableMapOf<PaymentGateway, PaymentGatewayHandler>()

    /**
     * Called from a gateway singleton's own `init` block — not something a
     * host normally calls directly. Handlers are singletons, so calling it
     * again for the same gateway is a harmless no-op.
     */
    fun register(handler: PaymentGatewayHandler) {
        handlers[handler.gateway] = handler
    }

    /**
     * Snapshot of gateways currently registered. On Android every included
     * gateway module has registered before any host code runs; on iOS
     * Razorpay gateways register pre-main and Juspay appears once the host
     * calls `JuspaySdk.initialize(...)`. Useful for a host that wants to
     * check what's available (e.g. to show/hide a button).
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
                                "${order.gateway}. Did you forget to include that " +
                                "gateway's module, or to call its initialize " +
                                "function if it has one?",
                        ),
                    ),
                ),
            )
        return handler.pay(order.gatewayConfig).map { LokalPaymentResult(order.gateway, it) }
    }

    const val VERSION: String = PAYMENT_SDK_VERSION
}
