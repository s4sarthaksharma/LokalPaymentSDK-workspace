package com.getlokalapp.paymentsdk.razorpay

/**
 * Raw callback forwarded as-is from the platform Razorpay SDK.
 * Classifying `code` into cancellation vs. a real failure happens one
 * layer up, in orchestration — not here — mirroring how matrimony keeps
 * that judgment out of the platform client itself.
 */
interface RazorpayPaymentResultListener {
    fun onPaymentSuccess(paymentId: String, orderId: String?, signature: String)
    fun onPaymentError(code: Int, description: String?)
}
