package com.getlokalapp.paymentsdk.model

/**
 * Gateway-specific cancel codes are classified into this by the platform
 * actual (e.g. Razorpay's Android "payment cancelled" error code) before
 * reaching common code — see PaymentResult.Cancelled.
 */
enum class CancelReason {
    USER_DISMISSED,
    UNKNOWN,
}
