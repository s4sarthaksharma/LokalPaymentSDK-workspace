package com.getlokalapp.paymentsdk.model

/**
 * What [com.getlokalapp.paymentsdk.LokalPaymentSdk.pay] emits: the
 * gateway-agnostic [PaymentResult] plus the [gateway] the routing layer
 * resolved for it.
 *
 * The gateway rides on this envelope rather than on [PaymentResult] because
 * it's routing metadata, not part of the payment outcome — only the generic
 * entry point knows it (the backend chose the gateway; the host didn't). A
 * host holding a specific gateway module's own SDK gets a bare
 * [PaymentResult] and already knows the gateway.
 *
 * [gateway] is nullable because routing can fail before any gateway is
 * resolved — e.g. the backend sent a gateway value that matches no known
 * [PaymentGateway] — in which case [result] is a [PaymentResult.Failure] and
 * there's genuinely no gateway to name.
 */
data class LokalPaymentResult(
    val gateway: PaymentGateway?,
    val result: PaymentResult,
)
