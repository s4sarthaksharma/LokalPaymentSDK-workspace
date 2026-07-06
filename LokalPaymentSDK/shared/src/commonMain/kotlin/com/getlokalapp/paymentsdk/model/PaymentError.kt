package com.getlokalapp.paymentsdk.model

/**
 * Normalized error surfaced by PaymentResult.Failure, regardless of
 * whether it came from the gateway SDK, order creation, or validation.
 */
data class PaymentError(
    val code: String?,
    val message: String,
)
