package com.getlokalapp.paymentsdk.razorpay

import com.getlokalapp.paymentsdk.model.CancelReason
import com.getlokalapp.paymentsdk.model.PaymentError
import com.getlokalapp.paymentsdk.model.PaymentResult

/**
 * Normalizes the raw Razorpay listener callbacks into a PaymentResult.
 * This is the "one layer up" classification that RazorpayPaymentResultListener's
 * doc deliberately keeps out of the platform clients — a user dismissing the
 * sheet (PAYMENT_CANCELLED) is a Cancelled, not a Failure.
 */
internal fun razorpaySuccess(paymentId: String, orderId: String?, signature: String): PaymentResult =
    PaymentResult.Success(paymentId = paymentId, orderId = orderId, signature = signature)

internal fun razorpayErrorToResult(code: Int, description: String?): PaymentResult =
    if (code == RazorpayErrorCodes.PAYMENT_CANCELLED) {
        PaymentResult.Cancelled(CancelReason.USER_DISMISSED)
    } else {
        PaymentResult.Failure(PaymentError(code = code.toString(), message = description ?: ""))
    }
