package com.getlokalapp.paymentsdk.model

/**
 * A [com.getlokalapp.paymentsdk.PaymentGatewayHandler]'s own answer to "can
 * you actually process a payment right now?" — checked live on every
 * [com.getlokalapp.paymentsdk.LokalPaymentSdk.gatewayStatus] call, unlike
 * [UnavailableGateway] registration (structural, decided once at process
 * start for platforms the handler never exists on at all). [NotReady] is for
 * a handler that exists but still needs host-supplied setup, e.g. Juspay
 * before its `initialize()` call.
 */
sealed interface GatewayReadiness {
    data object Ready : GatewayReadiness
    data class NotReady(val reasonCode: String, val reasonMessage: String) : GatewayReadiness
}
