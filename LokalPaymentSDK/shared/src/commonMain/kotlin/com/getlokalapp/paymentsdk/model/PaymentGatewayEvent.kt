package com.getlokalapp.paymentsdk.model

/**
 * What a gateway module's [com.getlokalapp.paymentsdk.PaymentGatewayHandler.pay] Flow emits.
 *
 * [UiPresented] is optional, non-terminal lifecycle information: a gateway emits it at most
 * once, always before [Terminal], to mean "I've taken over my own UI now (a full-screen sheet,
 * an in-place Fragment/WebView, ...) - it's safe to drop whatever 'please wait' UI you were
 * showing since the call to pay()." Most gateways never emit it at all (they launch their own
 * Activity/sheet, which already covers a host's loader with no signal needed); only a gateway
 * whose UI renders in-place - Juspay's HyperSDK Android Fragment being the one case today - has
 * a use for it.
 *
 * [Terminal] is the one required emission - exactly once, ending the flow - carrying the actual
 * [PaymentResult].
 */
sealed interface PaymentGatewayEvent {
    data object UiPresented : PaymentGatewayEvent
    data class Terminal(val result: PaymentResult) : PaymentGatewayEvent
}
