package com.getlokalapp.paymentsdk

import com.getlokalapp.paymentsdk.model.PaymentGateway
import com.getlokalapp.paymentsdk.model.PaymentResult
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject

/**
 * Implemented by each gateway module's own SDK class (e.g.
 * RazorpayCheckoutSdk, RazorpayUpiIntentSdk). Registers itself with
 * [LokalPaymentSdk] as soon as it's constructed — the host constructs
 * whichever handlers match the gateway modules it actually included, and
 * [LokalPaymentSdk] routes to them without depending on those modules.
 *
 * No UI handle to construct with — each concrete handler reads its own
 * current Activity/UIViewController from `:shared`'s hostcontext utilities
 * at call time instead of taking one from the host. [pay] receives only the
 * opaque `gateway_config` blob: [LokalPaymentSdk]
 * has already parsed the create-order envelope and routed by gateway, so the
 * handler never re-parses the response or re-checks the gateway. The blob
 * stays a raw [JsonObject] here because its typed shape (e.g.
 * RazorpayCheckoutConfig) is a gateway-module type [LokalPaymentSdk] can't
 * see — the owning module decodes it.
 */
interface PaymentGatewayHandler {
    val gateway: PaymentGateway
    fun pay(gatewayConfig: JsonObject): Flow<PaymentResult>

    /**
     * Call when this handler's underlying instance is going away, so
     * [LokalPaymentSdk] doesn't hold a stale reference. Default just
     * unregisters from [LokalPaymentSdk]; override to release any other
     * resources the concrete handler holds.
     */
    fun dispose() {
        LokalPaymentSdk.unregister(this)
    }
}
