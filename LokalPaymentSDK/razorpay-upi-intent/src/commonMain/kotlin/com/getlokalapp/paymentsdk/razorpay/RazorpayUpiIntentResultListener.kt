package com.getlokalapp.paymentsdk.razorpay

/**
 * Raw callback forwarded as-is from the platform Razorpay SDK's
 * Razorpay.submit(). Not shared with `:razorpay-checkout`'s listener —
 * submit()'s callback contract (and its cancellation code) differs from
 * Checkout.open()'s. Classifying `code` into cancellation vs. a real failure
 * happens one layer up, in RazorpayUpiIntentResultMapper.
 */
interface RazorpayUpiIntentResultListener {
    fun onPaymentSuccess(paymentId: String, orderId: String?, signature: String)
    fun onPaymentError(code: Int, description: String?)
}
