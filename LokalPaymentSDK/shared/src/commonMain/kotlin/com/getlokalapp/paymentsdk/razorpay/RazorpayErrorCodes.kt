package com.getlokalapp.paymentsdk.razorpay

/**
 * Razorpay's own checkout error codes, as passed to
 * RazorpayPaymentResultListener.onPaymentError. Verified against
 * matrimony-kmp's production Razorpay Checkout integration.
 */
object RazorpayErrorCodes {
    const val PAYMENT_CANCELLED = 0
}
