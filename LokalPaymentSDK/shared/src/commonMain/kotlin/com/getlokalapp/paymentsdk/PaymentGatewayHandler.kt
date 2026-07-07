package com.getlokalapp.paymentsdk

import com.getlokalapp.paymentsdk.model.PaymentGateway
import com.getlokalapp.paymentsdk.model.PaymentResult
import kotlinx.coroutines.flow.Flow

/**
 * Implemented by each gateway module's own SDK class (e.g.
 * RazorpayCheckoutSdk, RazorpayUpiIntentSdk). Registers itself with
 * [LokalPaymentSdk] as soon as it's constructed — the host constructs
 * whichever handlers match the gateway modules it actually included, and
 * [LokalPaymentSdk] routes to them without depending on those modules.
 *
 * Platform-specific setup (an Activity, a WebView, a PaymentPresenter) is a
 * constructor concern of the concrete handler, not part of this interface —
 * [pay] only ever takes the raw create-order response.
 */
interface PaymentGatewayHandler {
    val gateway: PaymentGateway
    fun pay(orderResponseJson: String): Flow<PaymentResult>

    /**
     * Call when this handler's underlying platform context (an Activity, a
     * PaymentPresenter) is going away, so [LokalPaymentSdk] doesn't hold a
     * stale reference. Default just unregisters from [LokalPaymentSdk];
     * override to release any other resources the concrete handler holds.
     */
    fun dispose() {
        LokalPaymentSdk.unregister(this)
    }
}
