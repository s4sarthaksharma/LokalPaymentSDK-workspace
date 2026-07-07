package com.getlokalapp.paymentsdk.razorpay

/**
 * Razorpay's UPI Intent (Razorpay.submit()) error codes, as passed to
 * RazorpayUpiIntentResultListener.onPaymentError. Verified against
 * matrimony-kmp's production Razorpay UPI Intent integration — note this
 * differs from `:razorpay-checkout`'s cancellation code (0).
 */
object RazorpayUpiIntentErrorCodes {
    const val PAYMENT_CANCELLED = 5
}
