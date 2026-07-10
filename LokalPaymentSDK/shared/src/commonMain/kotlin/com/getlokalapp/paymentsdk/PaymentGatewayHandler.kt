package com.getlokalapp.paymentsdk

import com.getlokalapp.paymentsdk.model.PaymentGateway
import com.getlokalapp.paymentsdk.model.PaymentResult
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject

/**
 * Implemented by each gateway module's own SDK singleton `object` (e.g.
 * RazorpayCheckoutSdk, RazorpayUpiIntentSdk, JuspaySdk). Each singleton
 * registers itself with [LokalPaymentSdk] in its `init` block, and the
 * gateway module arranges for that to run at app startup with zero host
 * code — a manifest-merged ContentProvider on Android, an
 * `@EagerInitialization` hook on iOS. The exception is a gateway that needs
 * host-supplied setup data (Juspay's init payload): there the host's one
 * `initialize(...)` call is the trigger. Registration is app-lifetime;
 * handlers are objects, so there is nothing to dispose or unregister.
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
}
