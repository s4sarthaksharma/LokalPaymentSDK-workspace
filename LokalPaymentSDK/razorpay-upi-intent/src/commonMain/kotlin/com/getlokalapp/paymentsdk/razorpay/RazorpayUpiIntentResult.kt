package com.getlokalapp.paymentsdk.razorpay

import com.getlokalapp.paymentsdk.model.CancelReason
import com.getlokalapp.paymentsdk.model.PaymentError
import com.getlokalapp.paymentsdk.model.PaymentResult

/**
 * Razorpay's UPI Intent (Razorpay.submit()) error codes, as passed to
 * [RazorpayUpiIntentResultListener.onPaymentError]. Verified against
 * matrimony-kmp's production Razorpay UPI Intent integration — note this
 * differs from `:razorpay-checkout`'s cancellation code (0).
 */
internal object RazorpayUpiIntentErrorCodes {
    const val PAYMENT_CANCELLED = 5
}

/**
 * Raw callback forwarded as-is from the platform Razorpay SDK's
 * Razorpay.submit(). Not shared with `:razorpay-checkout`'s listener —
 * submit()'s callback contract (and its cancellation code) differs from
 * Checkout.open()'s. Classifying `code` into cancellation vs. a real failure
 * happens one layer down, below.
 */
internal interface RazorpayUpiIntentResultListener {
    fun onPaymentSuccess(paymentId: String, orderId: String?, signature: String)
    fun onPaymentError(code: Int, description: String?)
}

/**
 * Normalizes the raw Razorpay UPI Intent listener callbacks into a
 * PaymentResult. Mirrors `:razorpay-checkout`'s RazorpayResultMapper, but
 * against this flow's own cancellation code.
 */
internal fun razorpayUpiIntentSuccess(paymentId: String, orderId: String?, signature: String): PaymentResult =
    PaymentResult.Success(paymentId = paymentId, orderId = orderId, signature = signature)

internal fun razorpayUpiIntentErrorToResult(code: Int, description: String?): PaymentResult =
    if (code == RazorpayUpiIntentErrorCodes.PAYMENT_CANCELLED) {
        PaymentResult.Cancelled(CancelReason.USER_DISMISSED)
    } else {
        PaymentResult.Failure(PaymentError(code = code.toString(), message = description ?: ""))
    }
