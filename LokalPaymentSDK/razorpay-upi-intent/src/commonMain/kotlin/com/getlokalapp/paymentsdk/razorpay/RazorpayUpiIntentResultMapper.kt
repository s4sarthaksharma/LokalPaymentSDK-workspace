package com.getlokalapp.paymentsdk.razorpay

import com.getlokalapp.paymentsdk.model.CancelReason
import com.getlokalapp.paymentsdk.model.PaymentError
import com.getlokalapp.paymentsdk.model.PaymentResult

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
