package com.getlokalapp.paymentsdk.model

/**
 * Terminal state emitted on the Flow returned by each gateway module's
 * pay() (e.g. RazorpayCheckoutSdk.pay(), RazorpayUpiIntentSdk.pay()).
 * Cancellation is a distinct branch from Failure by design — a user
 * dismissing the checkout sheet is not an error and should not route to
 * a failure UI.
 *
 * Success carries the raw gateway fields, not a validated outcome — the
 * SDK never calls a validate endpoint itself. The host app is expected
 * to take these straight to its own backend's validation call.
 */
sealed class PaymentResult {
    data class Success(val paymentId: String, val orderId: String?, val signature: String) : PaymentResult()
    data class Cancelled(val reason: CancelReason) : PaymentResult()
    data class Failure(val error: PaymentError) : PaymentResult()
}
